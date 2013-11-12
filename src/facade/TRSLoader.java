package facade;

import org.w3c.dom.*;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import plugins.text.ListeElement;
import plugins.text.TexteEditor;
import plugins.text.elements.*;

import javax.xml.parsers.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * TRS Loader.
 *
 * The constructor parses a TRS file. If parsing was successful, text and anchor info
 * can be retrieved through the public final attributes.
 */
class TRSLoader {
	/**
	 * Raw text contained in the Turn tags.
	 */
	public final String text;


	/**
	 * Mapping of TRS speaker IDs to speaker objects. This mapping is necessary
	 * because Locuteur_Info.id does not match TRS speaker IDs.
	 */
	public final HashMap<String, Locuteur_Info> speakers;


	/**
	 * All elements generated by parsing the TRS file.
	 */
	public final ListeElement elements;


	/**
	 * All non-text segments found in the TRS file.
	 */
	public final List<Segment> nonText;


	/**
	 * End time of last turn.
	 */
	public final float lastEnd;


	/**
	 * Parse a TRS file.
	 */
	public TRSLoader(String path)
			throws ParserConfigurationException, IOException, SAXException
	{
		Document doc = newXMLDocumentBuilder().parse(path);
		StringBuilder buffer = new StringBuilder();
		ListeElement allElements = new ListeElement();
		ArrayList<Segment> allNonText = new ArrayList<Segment>();

		// end time of last turn
		float lastEnd = -1f;

		speakers = loadSpeakers(doc.getElementsByTagName("Speakers").item(0));

		// Extract relevant information (speech text, Sync tags...) from Turn tags.
		NodeList turnList = doc.getElementsByTagName("Turn");

		for (int i = 0; i < turnList.getLength(); i++) {
			Element turn = (Element)turnList.item(i);
			Node child = turn.getFirstChild();

			Locuteur_Info currentSpeaker = speakers.get(turn.getAttribute("speaker"));
			boolean currentSpeakerIntroduced = false;

			float endTime = Float.parseFloat(turn.getAttribute("endTime"));
			if (endTime > lastEnd)
				lastEnd = endTime;

			while (null != child) {
				String name = child.getNodeName();

				// Speech text
				if (name.equals("#text")) {
					String text = TextParser.normalizeText(child.getTextContent().trim());
					if (!text.isEmpty()) {
						// Introduce current speaker with a line break and their name
						if (!currentSpeakerIntroduced) {
							if (buffer.length() > 0)
								buffer.append("\n");
							int pos = buffer.length();
							buffer.append(currentSpeaker.getName());
							currentSpeakerIntroduced = true;
							allElements.add(new Element_Locuteur(currentSpeaker.getId(), currentSpeaker.getId()));
							allNonText.add(new Segment(
									pos, pos + currentSpeaker.getName().length(), 0)); // Type 0 = speaker
						}

						buffer.append(' ');

						List<Segment> nonText = TextParser.findNonTextSegments(text,
								Arrays.asList(TexteEditor.DEFAULT_TYPES));
						ListeElement textElts = TextParser.parseString(text, nonText);

						// Offset this part before inserting it into the aggregate lists
						textElts.decalerTextPosi(buffer.length(), 0);
						for (Segment seg: nonText) {
							seg.deb += buffer.length();
							seg.fin += buffer.length();
						}

						allElements.addAll(textElts);
						allNonText.addAll(nonText);

						buffer.append(text);
					}
				}

				// Anchor. Placed on the last character in the word *PRECEDING* the sync point
				else if (name.equals("Sync")) {
					float second = Float.parseFloat(((Element)child).getAttribute("time"));

					String ancText = String.format("%d'%02d\"%03d",
							(int)(second/60f), (int)(second%60f), Math.round(second%1f * 1000f));

					if (buffer.length() > 0)
						buffer.append(' ');
					int pos = buffer.length();
					buffer.append(ancText);
					allNonText.add(new Segment(pos, pos + ancText.length(), 6));
					allElements.add(new Element_Ancre(second));
				}

				else if (name.equals("Comment")) {
					if (buffer.length() > 0)
						buffer.append(' ');
					int pos = buffer.length();
					String comment = "{" + ((Element)child).getAttribute("desc") + "}";
					buffer.append(comment);
					allElements.add(new Element_Commentaire(comment, pos));
					allNonText.add(new Segment(pos, pos + comment.length(), 1)); // Type 1 = comment
				}

				// Ignore unknown tag
				else {
					System.out.println("TRS WARNING: Ignoring inknown tag " + name);
				}

				// Onto next Turn child
				child = child.getNextSibling();
			}
		}

		text = buffer.toString();
		elements = allElements;
		nonText = allNonText;
		this.lastEnd = lastEnd;
	}


	private HashMap<String, Locuteur_Info> loadSpeakers(Node speakersNode) {
		HashMap<String, Locuteur_Info> info = new HashMap<String, Locuteur_Info>();

		Node spk = speakersNode.getFirstChild();

		for (; null != spk; spk = spk.getNextSibling()) {
			if (!spk.getNodeName().equals("Speaker"))
				continue;

			Element el = (Element)spk;
			String id      = el.getAttribute("id");
			String name    = el.getAttribute("name");
			boolean check  = el.getAttribute("check").toLowerCase().equals("yes");
			boolean type   = el.getAttribute("type").toLowerCase().equals("female");
			String dialect = el.getAttribute("dialect");
			String accent  = el.getAttribute("accent");
			String scope   = el.getAttribute("scope");

			info.put(id, new Locuteur_Info(
					(byte) info.size(), name, check, type, dialect, accent, scope));
		}

		return info;
	}


	/**
	 * Return a DocumentBuilder suitable to parsing a TRS file.
	 */
	private static DocumentBuilder newXMLDocumentBuilder() throws ParserConfigurationException {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setValidating(false);
		dbf.setNamespaceAware(true);
		dbf.setFeature("http://xml.org/sax/features/namespaces", false);
		dbf.setFeature("http://xml.org/sax/features/validation", false);
		dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
		dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		return dbf.newDocumentBuilder();
	}
}

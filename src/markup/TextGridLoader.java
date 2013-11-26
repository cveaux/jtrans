package markup;

import facade.Project;
import plugins.speechreco.aligners.sphiinx4.Alignment;
import plugins.text.elements.*;
import plugins.utils.FileUtils;
import plugins.utils.TimeConverter;

import java.io.*;
import java.util.*;
import java.util.regex.*;


/**
 * Parser for the TextGrid file format.
 */
public class TextGridLoader implements MarkupLoader {

	private class Early {
		int frame;
		int segment;
		int speaker;
		Alignment tier;
	}

	public Project parse(File file)
			throws ParsingException, IOException
	{
		Project project = new Project();
		BufferedReader reader = FileUtils.openFileAutoCharset(file);
		TextGridStateMachine machine = new TextGridStateMachine(reader);
		reader.close();

		for (String name: machine.tierNames)
			project.speakers.add(new Locuteur_Info((byte)project.speakers.size(), name));

		int nspk = machine.tiers.size();
		System.out.println(" *** SPEAKER COUNT: " + nspk + " ***");

		int[] upcoming = new int[nspk];
		for (int i = 0; i < nspk; i++)
			upcoming[i] = machine.tiers.get(i).getNbSegments() == 0? -1: 0;

		int currentSpeaker = -1;

		while (true) {
			Early early = null;

			// find earliest upcoming segment
			for (int i = 0; i < nspk; i++) {
				int segment = upcoming[i];
				if (segment < 0)
					continue;
				Alignment tier = machine.tiers.get(i);
				int frame = tier.getSegmentDebFrame(segment);
				if (early == null || frame < early.frame) {
					early = new Early();
					early.frame = frame;
					early.segment = segment;
					early.speaker = i;
					early.tier = tier;
				}
			}

			if (early == null)
				break;

			String text = RawTextLoader.normalizeText(early.tier.getSegmentLabel(early.segment));
			project.elts.addAll(RawTextLoader.parseString(text, project.types));

			project.elts.add(new Element_Ancre(TimeConverter.frame2sec(early.tier.getSegmentEndFrame(early.segment))));

			// Introduce current speaker
			if (currentSpeaker != early.speaker) {
				currentSpeaker = early.speaker;
				project.elts.add(new Element_Locuteur(project.speakers.get(early.speaker)));
			}
//			prevEndFrame = tier.getSegmentEndFrame(i);

			if (early.segment == early.tier.getNbSegments()-1)
				upcoming[early.speaker] = -1;
			else
				upcoming[early.speaker]++;
		}

		/*
		if (machine.tiers.size() > 1)
			throw new ParsingException("Can only import one TextGrid tier at a time for now.\n" +
					"(This file has " + machine.tiers.size() + " tiers.)");

		// Add sole speaker
		project.speakers.add(new Locuteur_Info((byte)0, machine.tierNames.get(0)));
		project.elts.add(new Element_Locuteur(project.speakers.get(0)));

		Alignment tier = machine.tiers.get(0);
		int prevEndFrame = -1;

		for (int i = 0; i < machine.tiers.get(0).getNbSegments(); i++) {
			int startFrame = tier.getSegmentDebFrame(i);
			if (prevEndFrame != startFrame)
				project.elts.add(new Element_Ancre(JTransAPI.frame2sec(startFrame)));

			String text = RawTextLoader.normalizeText(tier.getSegmentLabel(i));
			project.elts.addAll(RawTextLoader.parseString(text, project.types));

			project.elts.add(new Element_Ancre(JTransAPI.frame2sec(tier.getSegmentEndFrame(i))));
			prevEndFrame = tier.getSegmentEndFrame(i);
		}
		*/


		return project;
	}

	public String getFormat() {
		return "Praat TextGrid";
	}
}


class TextGridStateMachine {
	List<Alignment> tiers = new ArrayList<Alignment>();
	List<String> tierNames = new ArrayList<String>();


	private static enum State {
		FILE_HEADER_1,
		FILE_HEADER_2,
		FILE_DESCRIPTION,
		TIER_HEADER,
		TIER_DESCRIPTION,
		INTERVAL_HEADER,
		INTERVAL_DESCRIPTION,
		DONE
	}


	private static final String
			HALFPAT_STR   = "\\s*=\\s*\"(.*)\"",
			HALFPAT_FLOAT = "\\s*=\\s*(\\d+(|\\.\\d*))",
			HALFPAT_INT   = "\\s*=\\s*(\\d+)",
			HALFPAT_INDEX = "\\s*\\[\\s*(\\d+)\\s*\\]\\s*:";


	private static final Pattern
			PAT_SIZE = Pattern.compile("size" + HALFPAT_INT),
			PAT_ITEM = Pattern.compile("item" + HALFPAT_INDEX),
			PAT_NAME = Pattern.compile("name" + HALFPAT_STR),
			PAT_TEXT = Pattern.compile("text" + HALFPAT_STR),
			PAT_XMIN = Pattern.compile("xmin" + HALFPAT_FLOAT),
			PAT_XMAX = Pattern.compile("xmax" + HALFPAT_FLOAT),
			PAT_INTERVAL_COUNT = Pattern.compile("intervals: size" + HALFPAT_INT),
			PAT_INTERVAL_INDEX = Pattern.compile("intervals" + HALFPAT_INDEX);


	class Interval {
		float xmin = -1;
		float xmax = -1;
		String text = null;

		boolean isComplete() {
			return xmin >= 0 && xmax >= 0 && text != null;
		}

		boolean findMatch(String line) {
			Matcher m;

			m = PAT_TEXT.matcher(line);
			if (m.matches()) {
				text = m.group(1);
				return true;
			}

			m = PAT_XMIN.matcher(line);
			if (m.matches()) {
				xmin = Float.parseFloat(m.group(1));
				return true;
			}

			m = PAT_XMAX.matcher(line);
			if (m.matches()) {
				xmax = Float.parseFloat(m.group(1));
				return true;
			}

			return false;
		}
	}


	TextGridStateMachine(BufferedReader reader)
			throws IOException, ParsingException
	{
		State state = State.FILE_HEADER_1;
		int lineNumber = 0;
		Interval currentInterval = new Interval();
		Alignment currentTier = null;
		int remainingIntervals = -1;

		while (state != State.DONE) {
			lineNumber++;
			String line = reader.readLine();
			if (line == null)
				break;
			line = line.trim();
			String lcline = line.toLowerCase();
			
			switch (state) {
				case FILE_HEADER_1:
					if (!lcline.equals("file type = \"ootextfile\""))
						throw new ParsingException(lineNumber, line, "not an ooTextFile?");
					state = State.FILE_HEADER_2;
					break;

				case FILE_HEADER_2:
					if (!lcline.equals("object class = \"textgrid\""))
						throw new ParsingException(lineNumber, line, "not a TextGrid?");
					state = State.FILE_DESCRIPTION;
					break;
				
				case FILE_DESCRIPTION:
					if (lcline.equals("item []:")) {
						state = State.TIER_HEADER;
					} else if (line.isEmpty() ||
							PAT_XMIN.matcher(lcline).matches() ||
							PAT_XMAX.matcher(lcline).matches() ||
							PAT_SIZE.matcher(lcline).matches() ||
							lcline.equals("tiers? <exists>")) {
						;
					} else {
						throw new ParsingException(lineNumber, line,
								"unknown header line");
					}
					break;
				
				case TIER_HEADER:
					if (PAT_ITEM.matcher(lcline).matches()) {
						currentTier = new Alignment();
						tiers.add(currentTier);
						state = State.TIER_DESCRIPTION;
					} else {
						throw new ParsingException(lineNumber, line, "expecting tier header here");
					}
					break;
				
				case TIER_DESCRIPTION: {
					Matcher m;

					m = PAT_NAME.matcher(line);
					if (m.matches()) {
						tierNames.add(m.group(1));
						break;
					}

					m = PAT_INTERVAL_COUNT.matcher(line);
					if (m.matches()) {
						remainingIntervals = Integer.parseInt(m.group(1));
						state = State.INTERVAL_HEADER;
						break;
					}

					if (PAT_XMIN.matcher(lcline).matches() ||
							PAT_XMAX.matcher(lcline).matches() ||
							lcline.equals("class = \"intervaltier\"")) {
						;
					} else {
						throw new ParsingException(lineNumber, line, "unknown tier description line");
					}
					break;
				}

				case INTERVAL_HEADER:
					if (!PAT_INTERVAL_INDEX.matcher(lcline).matches()) {
						throw new ParsingException(lineNumber, line, "interval definition expected here");
					}
					state = State.INTERVAL_DESCRIPTION;
					remainingIntervals--;
					if (remainingIntervals < 0) {
						throw new ParsingException(lineNumber, line, "too many intervals");
					}
					break;

				case INTERVAL_DESCRIPTION:
					if (currentInterval.findMatch(line)) {
						if (currentInterval.isComplete()) {
							currentTier.addRecognizedSegment(
									currentInterval.text,
									TimeConverter.second2frame(currentInterval.xmin),
									TimeConverter.second2frame(currentInterval.xmax),
									null,
									null);
							currentInterval = new Interval();
							if (remainingIntervals == 0)
								state = State.TIER_HEADER;
							else
								state = State.INTERVAL_HEADER;
						}
					} else {
						throw new ParsingException(lineNumber, line, "unknown interval description line");
					}
					break;

				default:
					throw new ParsingException("unknown state " + state);
			}
		}
	}
}

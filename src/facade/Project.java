package facade;

import plugins.speechreco.aligners.sphiinx4.Alignment;
import plugins.text.ListeElement;
import plugins.text.elements.Locuteur_Info;
import plugins.text.regexp.TypeElement;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Used for easy serialization (for now).
 * Eventually this class should become more useful.
 * TODO: centralize project methods here
 */
public class Project {
	public List<Locuteur_Info> speakers;
	public ListeElement elts = new ListeElement();
	public String wavname;
	public String txtfile;
	public Alignment words = new Alignment();
	public Alignment phons = new Alignment();
	public List<TypeElement> types = new ArrayList<TypeElement>(Arrays.asList(DEFAULT_TYPES));


	public static final TypeElement DEFAULT_TYPES[] = {
			new TypeElement("Speaker", Color.GREEN,
					"(^|\\n)(\\s)*\\w\\d+\\s"),

			new TypeElement("Comment", Color.YELLOW,
					"\\{[^\\}]*\\}",
					"\\[[^\\]]*\\]",
					"\\+"),

			new TypeElement("Noise", Color.CYAN,
					"(\\w)*\\((\\w)*\\)(\\w)*",
					"\\s\\*\\*\\*\\s",
					"\\s\\*\\s"),

			new TypeElement("Overlap Start", Color.PINK,
					"<"),

			new TypeElement("Overlap End", Color.PINK,
					">"),

			new TypeElement("Punctuation", Color.ORANGE,
					"\\?",
					"\\:",
					"\\;",
					"\\,",
					"\\.",
					"\\!"),

			new TypeElement("Anchor", new Color(0xddffaa)),
	};


	public Project() {
	}

	public Project(ListeElement elts) {
		this.elts = elts;
	}


	public void refreshIndex() {
		words.buildIndex();
		phons.buildIndex();
		elts.refreshIndex();
	}
}
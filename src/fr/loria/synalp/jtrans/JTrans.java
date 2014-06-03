package fr.loria.synalp.jtrans;

import fr.loria.synalp.jtrans.align.AutoAligner;
import fr.loria.synalp.jtrans.align.FastLinearAligner;
import fr.loria.synalp.jtrans.align.RealisticPathLinearAligner;
import fr.loria.synalp.jtrans.align.ViterbiAligner;
import fr.loria.synalp.jtrans.utils.Cache;
import fr.loria.synalp.jtrans.gui.JTransGUI;
import fr.loria.synalp.jtrans.markup.in.*;
import fr.loria.synalp.jtrans.markup.out.MarkupSaver;
import fr.loria.synalp.jtrans.markup.out.MarkupSaverPool;
import fr.loria.synalp.jtrans.project.Project;
import fr.loria.synalp.jtrans.project.TurnProject;
import fr.loria.synalp.jtrans.utils.*;
import fr.loria.synalp.jtrans.align.Alignment;
import fr.loria.synalp.jtrans.graph.StateGraph;
import joptsimple.*;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.logging.LogManager;

public class JTrans {

	public static String logID = "_" + System.currentTimeMillis();
	public MarkupLoader loader;
	public File inputFile;
	public File audioFile;
	public File outputDir;
	public List<String> outputFormats;
	public List<String> anonymizedWords;
	public boolean clearTimes = false;
	public boolean align = true;
	public boolean runAnchorDiffTest = false;
	public boolean computeLikelihoods = false;
	public boolean refine = false;
	public boolean quiet = false;


	public final static String[] AUDIO_EXTENSIONS = "wav,ogg,mp3".split(",");
	public final static String[] MARKUP_EXTENSIONS = "jtr,trs,txt,textgrid".split(",");


	private static void printHelp(OptionParser parser) {
		try {
			parser.printHelpOn(System.out);
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}


	private static void listMarkupLoaders() {
		MarkupLoaderPool pool = MarkupLoaderPool.getInstance();

		StringBuilder vanilla = new StringBuilder("Vanilla markup loaders:");
		StringBuilder preproc = new StringBuilder("Preprocessors:");

		for (String name: pool.getNames()) {
			StringBuilder appendTo =
					pool.isVanillaLoader(name)? vanilla: preproc;
			appendTo.append("\n    ").append(name).append(" (")
					.append(pool.getDescription(name)).append(")");
		}

		System.out.println(vanilla);
		System.out.println(preproc);
	}


	private static void listMarkupSavers() {
		MarkupSaverPool pool = MarkupSaverPool.getInstance();
		System.out.println("Markup savers:");
		for (String name: pool.getNames()) {
			System.out.println(name + " (" + pool.getDescription(name) + ")");
		}
	}


	public JTrans(String[] args) throws ReflectiveOperationException {
		OptionParser parser = new OptionParser() {
			{
				accepts("h", "help screen").forHelp();

				accepts("f", "markup file (jtr, trs, txt, textgrid)")
						.withRequiredArg().ofType(File.class);

				accepts("a", "audio file (wav, ogg, mp3)")
						.withRequiredArg().ofType(File.class);

				accepts("q", "quietish (no progress display on stdout)");

				acceptsAll(
						Arrays.asList("A", "detect-audio"),
						"Automatically detect audio file in the same " +
						"directory as the markup file.");

				accepts("outdir", "output directory")
						.withRequiredArg().ofType(File.class)
						.defaultsTo(new File("."));

				accepts("outfmt", "Output format. Use this argument several " +
						"times to output to several different formats.")
						.withRequiredArg();

				acceptsAll(
						Arrays.asList("i", "infmt"),
						"Input markup loader. If omitted, guess vanilla " +
						"format from filename extension.")
						.withRequiredArg().describedAs("loader");

				accepts("list-infmt",
						"Displays a list of markup loaders to use with --infmt")
						.forHelp();

				accepts("list-outfmt",
						"Displays a list of markup savers to use with --outfmt")
						.forHelp();

				acceptsAll(
						Arrays.asList("C", "clear-times"),
						"Clear manual anchor times before aligning. " +
						"Will align with linear bridge.");

				acceptsAll(
						Arrays.asList("N", "no-align"),
						"Don't align after loading the project. Useful to " +
								"convert between formats.");

				accepts(
						"anchor-diff-test",
						"Regenerate anchor times and gauge deviation wrt. " +
								"reference times.");

				acceptsAll(
						Arrays.asList("B", "bypass-cache"),
						"Don't read objects from cache.");

				accepts("volatile-cache",
						"Mark cache files for deletion once they have been " +
						"used. Saves disk space when aligning large batches.");

				acceptsAll(
						Arrays.asList("L", "likelihood"),
						"Compute alignment likelihood");

				acceptsAll(
						Arrays.asList("M", "metropolis-hastings"),
						"Metropolis-Hastings post processing");

				acceptsAll(
						Arrays.asList("I", "ignore-overlaps"),
						"(Experimental) Force linear bridge when aligning " +
						"and ignore overlaps. Don't use unless you know what " +
						"you are doing!");

				acceptsAll(
						Arrays.asList("fastlin"),
						"Use dumb linear alignment instead of Viterbi. " +
						"Don't use unless you know what you are doing!");

				acceptsAll(
						Arrays.asList("reallin"),
						"Use \"realistic\" linear alignment (walking same path " +
						"as Viterbi), instead of Viterbi. Don't use unless " +
						"you know what you are doing!");

				accepts("z",
						"Anonymize word")
						.withRequiredArg().describedAs("word");
			}
		};


		OptionSet optset = parser.parse(args);

		//----------------------------------------------------------------------

		if (optset.has("h")) {
			printHelp(parser);
			System.exit(0);
		}

		if (optset.has("list-infmt")) {
			listMarkupLoaders();
			System.exit(0);
		}

		if (optset.has("list-outfmt")) {
			listMarkupSavers();
			System.exit(0);
		}

		//----------------------------------------------------------------------

		if (optset.has("bypass-cache")) {
			Cache.READ_FROM_CACHE = false;
			System.out.println("Won't read objects from cache.");
		}

		if (optset.has("volatile-cache")) {
			Cache.VOLATILE_CACHE = true;
		}

		if (optset.has("L")) {
			computeLikelihoods = true;
		}

		if (optset.has("metropolis-hastings")) {
			refine = true;
		}

		if (optset.has("ignore-overlaps")) {
			TurnProject.ALIGN_OVERLAPS = false;
			System.out.println("Will ignore overlaps.");
		}

		if (optset.has("fastlin") && optset.has("reallin")) {
			System.err.println("Can't use more than one aligner at once!");
			System.exit(1);
		} else if (optset.has("fastlin")) {
			Project.ALIGNER = FastLinearAligner.class;
			System.out.println("Will use fast linear alignment. " +
					"WARNING: massive loss of accuracy!");
		} else if (optset.has("reallin")) {
			Project.ALIGNER = RealisticPathLinearAligner.class;
			System.out.printf("Will use \"realistic\" linear alignment. " +
					"WARNING: massive loss of accuracy!");
		}

		inputFile = (File)optset.valueOf("f");
		audioFile = (File)optset.valueOf("a");
		outputDir = (File)optset.valueOf("outdir");
		clearTimes = optset.has("C");
		align = !optset.has("N");
		runAnchorDiffTest = optset.has("anchor-diff-test");
		quiet = optset.has("q");

		clearTimes |= runAnchorDiffTest;

		if (!align && runAnchorDiffTest) {
			System.err.println("Can't run the anchor diff test without aligning!");
			System.exit(1);
		}

		if (optset.has("infmt")) {
			String className = (String)optset.valueOf("infmt");
			loader = MarkupLoaderPool.getInstance().make(className);
		}

		outputFormats = (List<String>)optset.valuesOf("outfmt");

		anonymizedWords = (List<String>)optset.valuesOf("z");

		//----------------------------------------------------------------------

		for (Object o: optset.nonOptionArguments()) {
			String arg = (String)o;
			int dotIdx = arg.lastIndexOf('.');
			String ext = dotIdx >= 0? arg.substring(dotIdx+1): null;

			if (Arrays.asList(AUDIO_EXTENSIONS).contains(ext)) {
				if (audioFile != null) {
					throw new IllegalArgumentException("audio file already set");
				}
				audioFile = new File(arg);
			}

			else {
				if (inputFile != null) {
					throw new IllegalArgumentException("markup file already set");
				}
				inputFile = new File(arg);
			}
		}

		if (loader == null && inputFile != null) {
			String fn = inputFile.getName().toLowerCase();
			if (fn.endsWith(".jtr")) {
				loader = new JTRLoader();
			} else if (fn.endsWith(".trs")) {
				loader = new TRSLoader();
			} else if (fn.endsWith(".textgrid")) {
				loader = new TextGridLoader();
			} else if (fn.endsWith(".txt")) {
				loader = new RawTextLoader();
			}
		}

		if (optset.has("detect-audio") && audioFile == null && inputFile != null) {
			audioFile = FileUtils.detectHomonymousFile(
					inputFile, AUDIO_EXTENSIONS);
			System.out.println("Audio file detected: " + audioFile);
		}
	}


	public static void loadLoggingProperties() throws IOException {
		LogManager.getLogManager().readConfiguration(
				JTrans.class.getResourceAsStream("/logging.properties"));
	}


	public static void printAnchorDiffStats(List<Integer> diffs) {
		System.out.println("===== ANCHOR DIFF TEST =====");

		int absDiffSum = 0;
		int absDiffMax = 0;
		float sumOfSquares = 0;

		for (Integer d: diffs) {
			int abs = Math.abs(d);
			absDiffSum += abs;
			sumOfSquares += d * d;
			absDiffMax = Math.max(absDiffMax, abs);
		}

		float avg = (float)absDiffSum / diffs.size();
		float variance = sumOfSquares / diffs.size() - avg * avg;
		float stdDev = (float)Math.sqrt(variance);

		System.out.println("Abs diff avg.....: " + avg + " frames");
		System.out.println("Variance.........: " + variance);
		System.out.println("Std dev..........: " + stdDev);
		System.out.println("Worst abs diff...: " + absDiffMax);
	}


	/**
	 * Saves a project with output options specified on the command line.
	 */
	public void save(Project project)
			throws ReflectiveOperationException, IOException
	{
		outputDir.mkdirs();

		for (String fmt: outputFormats) {
			System.out.println("Output: format '" + fmt + "' to directory "
					+ outputDir);

			fmt = fmt.toLowerCase();
			String base = FileUtils.noExt(new File(outputDir,
					inputFile.getName()).getAbsolutePath());

			MarkupSaver saver = MarkupSaverPool.getInstance().make(fmt);
			saver.save(project, new File(base + saver.getExt()));
		}
	}


	/**
	 * Metropolis-Hastings Refinement Iteration Hook for accounting anchor differences
	 */
	private class AnchorDiffRIH implements Runnable {
		TurnProject project;
		TurnProject reference;
		PrintWriter pw = null;
		int iterations = 0;

		public AnchorDiffRIH(TurnProject p, TurnProject r) {
			this.project = p;
			this.reference = r;
		}

		public void run() {
			iterations++;

			if (null == pw) {
				String name = logID + "_anchordiff.txt";
				try {
					pw = new PrintWriter(new BufferedWriter(new FileWriter(name)));
				} catch (IOException ex) {
					throw new Error(ex);
				}
				System.err.println("anchordiff: " + name);
			}

			project.inferAnchors();
			List<Integer> diffs = reference.anchorFrameDiffs(project);

			int absDiffSum = 0;
			for (Integer d: diffs) {
				absDiffSum += Math.abs(d);
			}
			pw.println(absDiffSum / (float) diffs.size());

			if (iterations % 100 == 0) {
				pw.flush();
				try {
					save(project);
				} catch (Exception ex) {
					throw new Error(ex);
				}
			}
		}
	}


	public static void main(String args[]) throws Exception {
		ProgressDisplay progress = null;
		final Project project;
		final Project reference;
		final JTrans cli;
		final Alignment referenceAlignment;

		loadLoggingProperties();

		cli = new JTrans(args);

		if (ResourceInstaller.shouldReinstallResources()) {
			ResourceInstaller.installResources();
		}

		if (!cli.computeLikelihoods &&
				!cli.runAnchorDiffTest &&
				(cli.outputFormats == null || cli.outputFormats.isEmpty()))
		{
			CrossPlatformFixes.setNativeLookAndFeel();
			new JTransGUI(cli);
			return;
		}

		if (!cli.quiet) {
			progress = new PrintStreamProgressDisplay(2500, System.out);
		}

		logID += "_" + cli.inputFile.getName();

		project = cli.loader.parse(cli.inputFile);
		System.out.println("Project loaded.");

		if (null != cli.audioFile) {
			project.setAudio(cli.audioFile);
			System.out.println("Audio loaded.");
		}

		if (cli.clearTimes) {
			/* TODO: forced state graph: should be made accessible through a
			command line switch and generalized to linear alignments
			(eliminating the need for RealisticPathLinearAligner) */
			System.out.println("Computing reference path...");
			AutoAligner referenceAligner = project.getAligner(
					ViterbiAligner.class, progress, true); // KLUDGE!!! true is needed for kludgeReferenceScorer
			((TurnProject)project).align(referenceAligner, false);
			referenceAlignment = referenceAligner.getConcatenatedTimeline();
			referenceAligner.getTrainer().seal();
			project.clearAlignment();
			((TurnProject) project).clearAnchorTimes();
		} else {
			referenceAlignment = null;
		}

		if (cli.runAnchorDiffTest) {
			reference = cli.loader.parse(cli.inputFile);
		} else {
			reference = null;
		}

		AutoAligner aligner = null;
		if (cli.align) {
			aligner = project.getStandardAligner(progress, cli.computeLikelihoods);
			aligner.setRefine(cli.refine);
		}

		if (cli.runAnchorDiffTest && cli.refine) {
			assert aligner != null;
			assert reference != null;
			aligner.setRefinementIterationHook(
					cli.new AnchorDiffRIH((TurnProject)project, (TurnProject)reference));
		}

		if (cli.align) {
			assert aligner != null;

			System.out.println("Aligning...");
			if (null != referenceAlignment) {
				System.out.println("WARNING: Aligning with forced reference path!");
				StateGraph path = new StateGraph(referenceAlignment);
				assert path.isLinear();
				aligner.align(
						path,
						0,
						aligner.getFrameCount()-1,
						false); // not using the concatenated path here
			} else {
				project.align(aligner);
			}
			System.out.println("Alignment done.");
			if (cli.computeLikelihoods) {
				aligner.getTrainer().seal();
				System.out.println("Overall likelihood: " +
						aligner.getTrainer().getCumulativeLikelihood());
			}
		}

		if (cli.runAnchorDiffTest) {
			assert reference != null;
			((TurnProject)project).inferAnchors();
			List<Integer> diffs = reference.anchorFrameDiffs(project);
			printAnchorDiffStats(diffs);
		}

		if (!cli.anonymizedWords.isEmpty()) {
			for (String w: cli.anonymizedWords) {
				project.anonymizeWord(w);
			}

			AudioSystem.write(
					project.getAnonymizingAudioInputStream(),
					AudioFileFormat.Type.WAVE,
					new File("anonymized.wav"));
		}

		cli.save(project);
	}

}
package com.cloudera.dataflow.spark;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.KvCoder;
import com.google.cloud.dataflow.sdk.coders.StringDelegateCoder;
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.transforms.Count;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.Flatten;
import com.google.cloud.dataflow.sdk.transforms.Keys;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.RemoveDuplicates;
import com.google.cloud.dataflow.sdk.transforms.Values;
import com.google.cloud.dataflow.sdk.transforms.View;
import com.google.cloud.dataflow.sdk.transforms.WithKeys;
import com.google.cloud.dataflow.sdk.transforms.join.CoGbkResult;
import com.google.cloud.dataflow.sdk.transforms.join.CoGroupByKey;
import com.google.cloud.dataflow.sdk.transforms.join.KeyedPCollectionTuple;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionList;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.PDone;
import com.google.cloud.dataflow.sdk.values.PInput;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A test based on {@code TfIdf} from the SDK.
 */
public class TfIdfTest {

  private File inputDir;
  private File outputDir;

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  @Before
  public void setUp() throws IOException {
    inputDir = tmpDir.newFolder("in");
    copy("pg1112.txt", inputDir);
    copy("pg2264.txt", inputDir);
    outputDir = tmpDir.newFolder("out");
    outputDir.delete();
  }

  private static void copy(String resourceName, File dir) throws IOException {
    File dest = new File(dir, resourceName);
    Resources.copy(Resources.getResource(resourceName), Files.asByteSink(dest).openStream());
  }

  @Test
  public void testRun() throws Exception {
    Pipeline pipeline = Pipeline.create(PipelineOptionsFactory.create());

    pipeline.getCoderRegistry().registerCoder(URI.class, StringDelegateCoder.of(URI.class));

    pipeline
        .apply(new ReadDocuments(listInputDocuments()))
        .apply(new ComputeTfIdf())
        .apply(new WriteTfIdf(outputDir.toURI().toString()));

    SparkPipelineRunner.create().run(pipeline);

    for (File f : outputDir.listFiles(new FileFilter() {
      @Override public boolean accept(File pathname) {
        return pathname.getName().startsWith("part-");
      }
    })) {
      for (String line : Files.readLines(f, Charsets.UTF_8)) {
        if (line.startsWith("love,")) {
          assertTrue("Incorrect term frequency", line.endsWith("0.003484"));
          return;
        }
      }

    }
    fail("Could not find 'love' in output.");
  }

  public Set<URI> listInputDocuments() {
    Set<URI> uris = new HashSet<>();
    File directory = inputDir;
    for (String entry : directory.list()) {
      File path = new File(directory, entry);
      uris.add(path.toURI());
    }
    return uris;
  }

  /**
   * Reads the documents at the provided uris and returns all lines
   * from the documents tagged with which document they are from.
   */
  public static class ReadDocuments
      extends PTransform<PInput, PCollection<KV<URI, String>>> {

    private final Iterable<URI> uris;

    public ReadDocuments(Iterable<URI> uris) {
      this.uris = uris;
    }

    @Override
    public Coder<?> getDefaultOutputCoder() {
      return KvCoder.of(StringDelegateCoder.of(URI.class), StringUtf8Coder.of());
    }

    @Override
    public PCollection<KV<URI, String>> apply(PInput input) {
      Pipeline pipeline = input.getPipeline();

      // Create one TextIO.Read transform for each document
      // and add its output to a PCollectionList
      PCollectionList<KV<URI, String>> urisToLines =
          PCollectionList.empty(pipeline);

      // TextIO.Read supports:
      //  - file: URIs and paths locally
      //  - gs: URIs on the service
      for (URI uri : uris) {
        String uriString;
        if ("file".equals(uri.getScheme())) {
          uriString = new File(uri).getPath();
        } else {
          uriString = uri.toString();
        }

        PCollection<KV<URI, String>> oneUriToLines = pipeline
            .apply(TextIO.Read.from(uriString)
                .named("TextIO.Read(" + uriString + ")"))
            .apply(WithKeys.<URI, String>of(uri).withName("WithKeys(" + uriString + ")"));

        urisToLines = urisToLines.and(oneUriToLines);
      }

      return urisToLines.apply(Flatten.<KV<URI, String>>create());
    }
  }

  /**
   * A transform containing a basic TF-IDF pipeline. The input consists of KV objects
   * where the key is the document's URI and the value is a piece
   * of the document's content. The output is mapping from terms to
   * scores for each document URI.
   */
  public static class ComputeTfIdf
      extends PTransform<PCollection<KV<URI, String>>, PCollection<KV<String, KV<URI, Double>>>> {

    @Override
    public PCollection<KV<String, KV<URI, Double>>> apply(
        PCollection<KV<URI, String>> uriToContent) {

      // Compute the total number of documents, and
      // prepare this singleton PCollectionView for
      // use as a side input.
      final PCollectionView<Long> totalDocuments =
          uriToContent
              .apply(Keys.<URI>create().withName("GetURIs"))
              .apply(RemoveDuplicates.<URI>create().withName("RemoveDuplicateDocs"))
              .apply(Count.<URI>globally())
              .apply(View.<Long>asSingleton());

      // Create a collection of pairs mapping a URI to each
      // of the words in the document associated with that that URI.
      PCollection<KV<URI, String>> uriToWords = uriToContent
          .apply(ParDo.named("SplitWords").of(
              new DoFn<KV<URI, String>, KV<URI, String>>() {
                @Override
                public void processElement(ProcessContext c) {
                  URI uri = c.element().getKey();
                  String line = c.element().getValue();
                  for (String word : line.split("\\W+")) {
                    if (LOG.isDebugEnabled()) {
                      // Log messages when the word "love" is found.
                      if ("love".equals(word.toLowerCase(Locale.ENGLISH))) {
                        LOG.debug("Found {}", word.toLowerCase(Locale.ENGLISH));
                      }
                    }

                    if (!word.isEmpty()) {
                      c.output(KV.of(uri, word.toLowerCase(Locale.ENGLISH)));
                    }
                  }
                }
              }));

      // Compute a mapping from each word to the total
      // number of documents in which it appears.
      PCollection<KV<String, Long>> wordToDocCount = uriToWords
          .apply(RemoveDuplicates.<KV<URI, String>>create().withName("RemoveDuplicateWords"))
          .apply(Values.<String>create())
          .apply(Count.<String>perElement().withName("CountDocs"));

      // Compute a mapping from each URI to the total
      // number of words in the document associated with that URI.
      PCollection<KV<URI, Long>> uriToWordTotal = uriToWords
          .apply(Keys.<URI>create().withName("GetURIs2"))
          .apply(Count.<URI>perElement().withName("CountWords"));

      // Count, for each (URI, word) pair, the number of
      // occurrences of that word in the document associated
      // with the URI.
      PCollection<KV<KV<URI, String>, Long>> uriAndWordToCount = uriToWords
          .apply(Count.<KV<URI, String>>perElement().withName("CountWordDocPairs"));

      // Adjust the above collection to a mapping from
      // (URI, word) pairs to counts into an isomorphic mapping
      // from URI to (word, count) pairs, to prepare for a join
      // by the URI key.
      PCollection<KV<URI, KV<String, Long>>> uriToWordAndCount = uriAndWordToCount
          .apply(ParDo.named("ShiftKeys").of(
              new DoFn<KV<KV<URI, String>, Long>, KV<URI, KV<String, Long>>>() {
                @Override
                public void processElement(ProcessContext c) {
                  URI uri = c.element().getKey().getKey();
                  String word = c.element().getKey().getValue();
                  Long occurrences = c.element().getValue();
                  c.output(KV.of(uri, KV.of(word, occurrences)));
                }
              }));

      // Prepare to join the mapping of URI to (word, count) pairs with
      // the mapping of URI to total word counts, by associating
      // each of the input PCollection<KV<URI, ...>> with
      // a tuple tag. Each input must have the same key type, URI
      // in this case. The type parameter of the tuple tag matches
      // the types of the values for each collection.
      final TupleTag<Long> wordTotalsTag = new TupleTag<>();
      final TupleTag<KV<String, Long>> wordCountsTag = new TupleTag<>();
      KeyedPCollectionTuple<URI> coGbkInput = KeyedPCollectionTuple
          .of(wordTotalsTag, uriToWordTotal)
          .and(wordCountsTag, uriToWordAndCount);

      // Perform a CoGroupByKey (a sort of pre-join) on the prepared
      // inputs. This yields a mapping from URI to a CoGbkResult
      // (CoGroupByKey Result). The CoGbkResult is a mapping
      // from the above tuple tags to the values in each input
      // associated with a particular URI. In this case, each
      // KV<URI, CoGbkResult> group a URI with the total number of
      // words in that document as well as all the (word, count)
      // pairs for particular words.
      PCollection<KV<URI, CoGbkResult>> uriToWordAndCountAndTotal = coGbkInput
          .apply(CoGroupByKey.<URI>create().withName("CoGroupByURI"));

      // Compute a mapping from each word to a (URI, term frequency)
      // pair for each URI. A word's term frequency for a document
      // is simply the number of times that word occurs in the document
      // divided by the total number of words in the document.
      PCollection<KV<String, KV<URI, Double>>> wordToUriAndTf = uriToWordAndCountAndTotal
          .apply(ParDo.named("ComputeTermFrequencies").of(
              new DoFn<KV<URI, CoGbkResult>, KV<String, KV<URI, Double>>>() {
                @Override
                public void processElement(ProcessContext c) {
                  URI uri = c.element().getKey();
                  Long wordTotal = c.element().getValue().getOnly(wordTotalsTag);

                  for (KV<String, Long> wordAndCount
                      : c.element().getValue().getAll(wordCountsTag)) {
                    String word = wordAndCount.getKey();
                    Long wordCount = wordAndCount.getValue();
                    Double termFrequency = wordCount.doubleValue() / wordTotal.doubleValue();
                    c.output(KV.of(word, KV.of(uri, termFrequency)));
                  }
                }
              }));

      // Compute a mapping from each word to its document frequency.
      // A word's document frequency in a corpus is the number of
      // documents in which the word appears divided by the total
      // number of documents in the corpus. Note how the total number of
      // documents is passed as a side input; the same value is
      // presented to each invocation of the DoFn.
      PCollection<KV<String, Double>> wordToDf = wordToDocCount
          .apply(ParDo
              .named("ComputeDocFrequencies")
              .withSideInputs(totalDocuments)
              .of(new DoFn<KV<String, Long>, KV<String, Double>>() {
                @Override
                public void processElement(ProcessContext c) {
                  String word = c.element().getKey();
                  Long documentCount = c.element().getValue();
                  Long documentTotal = c.sideInput(totalDocuments);
                  Double documentFrequency = documentCount.doubleValue()
                      / documentTotal.doubleValue();

                  c.output(KV.of(word, documentFrequency));
                }
              }));

      // Join the term frequency and document frequency
      // collections, each keyed on the word.
      final TupleTag<KV<URI, Double>> tfTag = new TupleTag<>();
      final TupleTag<Double> dfTag = new TupleTag<>();
      PCollection<KV<String, CoGbkResult>> wordToUriAndTfAndDf = KeyedPCollectionTuple
          .of(tfTag, wordToUriAndTf)
          .and(dfTag, wordToDf)
          .apply(CoGroupByKey.<String>create());

      // Compute a mapping from each word to a (URI, TF-IDF) score
      // for each URI. There are a variety of definitions of TF-IDF
      // ("term frequency - inverse document frequency") score;
      // here we use a basic version that is the term frequency
      // divided by the log of the document frequency.

      return wordToUriAndTfAndDf
          .apply(ParDo.named("ComputeTfIdf").of(
              new DoFn<KV<String, CoGbkResult>, KV<String, KV<URI, Double>>>() {
                @Override
                public void processElement(ProcessContext c) {
                  String word = c.element().getKey();
                  Double df = c.element().getValue().getOnly(dfTag);

                  for (KV<URI, Double> uriAndTf : c.element().getValue().getAll(tfTag)) {
                    URI uri = uriAndTf.getKey();
                    Double tf = uriAndTf.getValue();
                    Double tfIdf = tf * Math.log(1 / df);
                    c.output(KV.of(word, KV.of(uri, tfIdf)));
                  }
                }
              }));
    }

    // Instantiate Logger.
    // It is suggested that the user specify the class name of the containing class
    // (in this case ComputeTfIdf).
    private static final Logger LOG = LoggerFactory.getLogger(ComputeTfIdf.class);
  }

  /**
   * A {@link PTransform} to write, in CSV format, a mapping from term and URI
   * to score.
   */
  public static class WriteTfIdf
      extends PTransform<PCollection<KV<String, KV<URI, Double>>>, PDone> {

    private final String output;

    public WriteTfIdf(String output) {
      this.output = output;
    }

    @Override
    public PDone apply(PCollection<KV<String, KV<URI, Double>>> wordToUriAndTfIdf) {
      return wordToUriAndTfIdf
          .apply(ParDo.named("Format").of(new DoFn<KV<String, KV<URI, Double>>, String>() {
            @Override
            public void processElement(ProcessContext c) {
              c.output(String.format("%s,\t%s,\t%f",
                  c.element().getKey(),
                  c.element().getValue().getKey(),
                  c.element().getValue().getValue()));
            }
          }))
          .apply(TextIO.Write
              .to(output)
              .withSuffix(".csv"));
    }
  }
}

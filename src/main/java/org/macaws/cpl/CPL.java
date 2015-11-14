package org.macaws.cpl;

import opennlp.tools.cmdline.PerformanceMonitor;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.WhitespaceTokenizer;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import org.macaws.ke.Controller;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Malintha on 8/3/2015.
 */
public class CPL {
    static ClassLoader classLoader = null;
    static InputStream modelIn = null;
    static PerformanceMonitor perfMon = null;
    static POSModel model = null;
    static ObjectStream<String> lineStream;
    static POSTaggerME tagger;

    public static void initialize() throws IOException {
        classLoader = Thread.currentThread().getContextClassLoader();
        modelIn = classLoader.getResourceAsStream("openNLP/en-pos-maxent.bin");
        perfMon = new PerformanceMonitor(System.err, "sent");
        model = new POSModel(modelIn);
        tagger = new POSTaggerME(model);
    }


    public static void main(String[] args) throws IOException {
        ArrayList<String> batsman = new ArrayList<String>();
        ArrayList<String> bowler = new ArrayList<String>();
        ArrayList<String> team = new ArrayList<String>();
        initialize();

        batsman.add("Kumar Sangakkara");
        batsman.add("Mahela Jayawardene");
        batsman.add("Sachin Tendulkar");

        bowler.add("Ishant Sharma");
        bowler.add("Brett Lee");
        bowler.add("Shoaib Akhtar");
        bowler.add("Mitchell Johnson");

        team.add("Sri Lanka");
        team.add("India");
        team.add("Pakistan");
        team.add("Australia");

        HashMap<String, ArrayList> instances = new HashMap<String, ArrayList>();
        instances.put("batsman", batsman);
        instances.put("bowler", bowler);
        instances.put("team", team);

        Controller c = new Controller();
        ArrayList<String> s = c.preProcess(1);

        HashMap<String, ArrayList<String>> patterns = new HashMap<String, ArrayList<String>>();

        Iterator it = instances.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, ArrayList> pair = (Map.Entry<String, ArrayList>) it.next();
            String category = pair.getKey();
            ArrayList<String> instancesInCategory = pair.getValue();
            ArrayList<String> instancesInSentences = new ArrayList<>();
            for (String ins : instancesInCategory) {
                for (String sentence : s) {
                    if (sentence != null) {
                        if (sentence.contains(ins)) {
                            instancesInSentences.add(sentence.trim());
                        }
                    } else {
                        continue;
                    }
                }
                //extract patterns
                extractFollowingPattern(ins, instancesInSentences);
            }
        }
    }


    //    Extract Following pattern
    public static ArrayList<String> extractFollowingPattern(String instance, ArrayList<String> sentencesList) {

        ArrayList<String> patternList = new ArrayList<>();
        ArrayList<String[]> taggedArray = new ArrayList<>();
        HashMap<String, String> posVsSent = new HashMap<>();
        Pattern removeBracketcontent = Pattern.compile("\\((.*)\\)\\s");
        Matcher matcher;
//        String pa = "(NN.){1,4}\\s(VB.)\\s(DT|JJ.{0,1}|RB|\\s)*((NN.{0,1}|\\s)*|(IN|\\s){0,1})";
//        String pa = "(NN.{0,1}){1,4}\\s(VB.|NN)\\s(IN|DT|JJ.{0,1}|RB|\\s)*((NN.{0,1}|\\s)*|(IN|\\s){0,1})";
        String pa = "(NN.{0,1}){1,4}\\s(VB.|NN|IN|CC)\\s(IN|DT|JJ.{0,1}|RB|NN.|\\s)*((NN.{0,1}|\\s)*|(IN|\\s){0,1})";
        Pattern pat = Pattern.compile(pa);
        Matcher m;

        int juncIndex = 0;
        String instanceFirstName;

//        if instance has more than 1 word eg : Ishant Sharma
        if (instance.split(" ").length > 1) {
            instanceFirstName = instance.split(" ")[0];
        } else {
            instanceFirstName = instance;
        }

        try {
            for (String s : sentencesList) {
                s = s.trim();
                matcher = removeBracketcontent.matcher(s);
                while (matcher.find()) {
                    s = matcher.replaceAll("");
                }

                juncIndex = s.indexOf(instance);
//                System.out.println("#### " + s + " ### " + instance + " ### " + juncIndex);
                String followingSub;

                if (juncIndex != -1) {
                    followingSub = s.substring(juncIndex);
                    lineStream = new PlainTextByLineStream(new StringReader(followingSub.trim()));

                    String line;
                    while ((line = lineStream.read()) != null) {
                        String whitespaceTokenizerLine[] = WhitespaceTokenizer.INSTANCE.tokenize(line);
                        String[] tags = tagger.tag(whitespaceTokenizerLine);
                        taggedArray.add(tags);
                        String tagSentence = "";
                        for (String t : tags) {
                            tagSentence += t + " ";
                        }
//                    System.out.println(followingSub + "\n" + tagSentence + "\n");
                        posVsSent.put(followingSub, tagSentence.trim());
//                    perfMon.incrementCounter();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        Iterator posVsSentIterator = posVsSent.entrySet().iterator();

        while (posVsSentIterator.hasNext()) {
            Map.Entry<String, String> pair = (Map.Entry<String, String>) posVsSentIterator.next();
            String p = pair.getValue();
            String s = pair.getKey();
            m = pat.matcher(p);
            String posLen = "";
            while (m.find()) {
                posLen += m.group();
            }
            int len = posLen.split(" ").length;

            String[] sentW = s.split(" ");
            for (int i = 0; i < sentW.length; i++) {
            try {
                if (sentW[i].equals(instanceFirstName)) {
//                    System.out.println("### "+s+" ### "+instance+" ### "+len);
                    int cur = i;
                    while (i <= cur + len) {
                        System.out.print(sentW[i] + " ");
                        i++;
                    }
                    System.out.println("###"+s+"###"+instance);
                    break;
                }
            }
            catch(ArrayIndexOutOfBoundsException e) {
                System.out.println("### "+s+" ### "+instance+" ### "+len);
            }
            }

        }

        return null;
    }
}







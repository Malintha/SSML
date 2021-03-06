package org.macaws.cpl;

import opennlp.tools.cmdline.PerformanceMonitor;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.WhitespaceTokenizer;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import org.macaws.ke.Controller;

import java.io.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Created by Malintha on 8/3/2015.
 */
public class CPL {
    static Connection con;
    static int currentIteration;
    static ArrayList<String> s;
    static CPLUtils cplUtils;
    static PatternMatchRunnable patternMatchRunnable;

    public static void main(String[] args) throws Exception {

        CPL cpl = new CPL();
        cpl.initialize(4);
//        cplUtils.writeCorpusToFile(1);
//        cpl.runCPL();
//        cpl.extractInstancesFromPromotedPatterns(4);
        cpl.promoteInstances();
    }

    /**
     * Initialize the variables for CPL algorithm
     *
     * @throws Exception
     */

    public void initialize(int currentIteration) throws Exception {
        cplUtils = new CPLUtils();
        cplUtils.setSentCorpus(currentIteration);
        con = DBCon.getInstance();
        this.currentIteration = currentIteration;
        s = new ArrayList<String>();

    }


    /**
     * CPL algorithm
     *
     * @throws Exception
     */
    public void runCPL() throws Exception {
        ArrayList<String> batsman = new ArrayList<String>();
        ArrayList<String> bowler = new ArrayList<String>();
        ArrayList<String> team = new ArrayList<String>();

        //getting instances from ontology

        batsman.add("Kumar Sangakkara");
        batsman.add("Mahela Jayawardene");
        batsman.add("Sachin Tendulkar");
        batsman.add("Brendon McCullum");

        bowler.add("Ishant Sharma");
        bowler.add("Brett Lee");
        bowler.add("Shoaib Akhtar");

        team.add("Sri Lanka");
        team.add("India");
        team.add("Pakistan");
        team.add("Australia");

        HashMap<String, ArrayList> instances = new HashMap<String, ArrayList>();
        instances.put("batsman", batsman);
        instances.put("bowler", bowler);
        instances.put("team", team);

        Controller c = new Controller();
        s = c.preProcess(this.currentIteration);

        HashMap<String, ArrayList<String>> candidatePatterns = new HashMap<>();
        candidatePatterns.put("batsman", new ArrayList<String>());
        candidatePatterns.put("bowler", new ArrayList<String>());
        candidatePatterns.put("team", new ArrayList<String>());

        ArrayList<String> curList;
        ArrayList<String> incomingList;
        Iterator it = instances.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, ArrayList> pair = (Map.Entry<String, ArrayList>) it.next();
            String category = pair.getKey();
            ArrayList<String> instancesInCategory = pair.getValue();
            ArrayList<String> instancesInSentences = new ArrayList<>();
            for (String ins : instancesInCategory) {
//                get instance first/second name
                String[] parts = ins.split(" ");
                String fname = "";
                String lname = "";
                if (parts.length > 1) {
                    fname = parts[0];
                    lname = parts[parts.length - 1];
                } else {
                    fname = lname = parts[0];
                }

                for (String sentence : s) {
                    if (sentence != null) {
                        if (sentence.contains(fname) || sentence.contains(lname)) {
                            instancesInSentences.add(sentence.trim());
                        }
                    } else {
                        continue;
                    }
                }
                //extract patterns
                curList = candidatePatterns.get(category);
                incomingList = extractFollowingPattern(ins, fname, lname, instancesInSentences);

                if (incomingList.size() != 0) {
                    for (int i = 0; i < incomingList.size(); i++) {
                        curList.add(incomingList.get(i));
                    }
                    candidatePatterns.put(category, curList);
                }
            }
        }

        //store patterns in db
        Iterator candidatePatIterator = candidatePatterns.entrySet().iterator();
        PreparedStatement ps = con.prepareStatement("INSERT INTO candidate_patterns(Category,Pattern) VALUES (?,?)");

        while (candidatePatIterator.hasNext()) {
            Map.Entry<String, ArrayList> pair = (Map.Entry<String, ArrayList>) candidatePatIterator.next();
            ArrayList<String> candidatePatternList = pair.getValue();
            String category = pair.getKey();

            for (String p : candidatePatternList) {
                ps.setString(1, category);
                ps.setString(2, p);
                ps.executeUpdate();
            }
        }

        //promote patterns

        //extract instances from promoted patterns - done

        //promote instances
    }

    public void promoteInstances() throws SQLException {
        //retrieve candidate instances that are not in promoted list. if a part of the name appears,
        // check if the full name has occurred in the text corpus more than once, append all the patterns belonged
        // to that category to promoted patterns list
        //
        //count belonging categories
        //
        // if appears more than 2 times of a mutually exclusive occurrances promote
        HashMap<Integer,ContextualInstance> candidateInstancesList = new HashMap<>();
        PreparedStatement psRetCandidate = con.prepareStatement("SELECT * FROM candidate_instances");
        ResultSet rsCandidateIns = psRetCandidate.executeQuery();
        PreparedStatement psRetPromotedIns = con.prepareStatement("SELECT * FROM promoted_instances");
        ResultSet rsPromotedIns = psRetPromotedIns.executeQuery();
        ArrayList<String> promotedInsList = new ArrayList();

        while(rsPromotedIns.next()){
            promotedInsList.add(rsPromotedIns.getString("instance"));
        }

        System.out.println(promotedInsList);

        HashMap<String,ContextualInstance> candidateContextualInstances = new HashMap<>();

        while(rsCandidateIns.next()){
            String suggestedfullName;
            int candidateId = rsCandidateIns.getInt("id");
            String instanceName = rsCandidateIns.getString("instance").trim();
            String suggestedCategory = rsCandidateIns.getString("category").trim();
            String pattern = rsCandidateIns.getString("matching_patterns").trim();
            boolean isAlreadyPromoted = false;
            for(String promotedIns:promotedInsList){
                String candidateName = instanceName;
                if(promotedIns.contains(candidateName)){
                    isAlreadyPromoted = true;
                    suggestedfullName = promotedIns;
                    //if suggestedFullName appears more than 3 times in db it becomes the full name and add all the patterns
                    //to that instance.
                    int occurrence = cplUtils.getOccurancesInCorpus(suggestedfullName);
//                    System.out.println(candidateName+" | "+suggestedfullName+" | "+occurrence);
                    if(occurrence>3){
                        this.updatePromotedInstance(suggestedfullName,rsCandidateIns.getString("category"),rsCandidateIns.getString("matching_patterns"));

                    }
                    break;
                }
            }

            if(isAlreadyPromoted)
                continue;

            //get count of the categories that it co-occur with
            //add to a hashmap <instance name, contextualInstance> // add pattern to patterns list CI.addpattern(Category,pattern)
            //if found again, increase particular category occurrence contextualInstance.increaseOccurrence(String Category)
            ContextualInstance contextualInstance = candidateContextualInstances.get(instanceName);
            if(contextualInstance==null){
                candidateContextualInstances.put(instanceName, new ContextualInstance(candidateId, instanceName, suggestedCategory, pattern));
            }
            else{

                contextualInstance.patternList.put(pattern,suggestedCategory);
                contextualInstance.increseCategoryCount(suggestedCategory);

            }
        }

        Iterator iterator = candidateContextualInstances.entrySet().iterator();

        while(iterator.hasNext()){
            //name vs object
            Map.Entry<String,ContextualInstance> pair = (Map.Entry<String, ContextualInstance>) iterator.next();
//            System.out.println(pair.getKey()+" | "+pair.getValue().categoryCount);
            ContextualInstance promotedInstance =  pair.getValue();
            HashMap<String,Float> categories = promotedInstance.calculateCategory();
            Iterator promotedInstanceCategoryIt = categories.entrySet().iterator();
            //this is the promoted category vs certainty
            while(promotedInstanceCategoryIt.hasNext()){
                Map.Entry<String,Float> pair1 = (Map.Entry<String, Float>) promotedInstanceCategoryIt.next();
                promotedInstance.getCategoricalPatterns(pair1.getKey());

                String instanceName = pair.getKey();
                String promotedCategory = pair1.getKey();
                float certainty = pair1.getValue();
                ArrayList<String> supportivepatterns = promotedInstance.getCategoricalPatterns(promotedCategory);

                System.out.println("instance: "+pair.getKey()+" | "+promotedCategory+" certainty: "+certainty+" supportive patterns: "+supportivepatterns);
                System.out.println("## "+promotedInstance.patternList);
                System.out.println();

                addpromotedInstancesToKB(instanceName,promotedCategory,supportivepatterns,certainty,"CPL");



            }
        }
    }

    public void addpromotedInstancesToKB(String aInstance, String aCategory, ArrayList<String> aSupportivePatterns, float aCertainty, String aLearner) throws SQLException {
        PreparedStatement psInserttoPromotedInstances = con.prepareStatement("insert into promoted_instances(instance,category,patterns,certainty,seed,learnedIteration) values (?,?,?,?,?,?)");
        psInserttoPromotedInstances.setString(1,aInstance);
        psInserttoPromotedInstances.setString(2,aCategory);
        String supportivePatterns = "";
        for(int i=0;i<aSupportivePatterns.size();i++){
            if(i!=aSupportivePatterns.size()-1){
                supportivePatterns+= aSupportivePatterns.get(i)+", ";
            }
            else{
                supportivePatterns+=aSupportivePatterns.get(i);
            }
        }

        psInserttoPromotedInstances.setString(3,supportivePatterns);
        psInserttoPromotedInstances.setFloat(4,aCertainty);
        psInserttoPromotedInstances.setString(5, aLearner);
        psInserttoPromotedInstances.setInt(6,currentIteration);
        psInserttoPromotedInstances.executeUpdate();

        //Now add the instance into ontology

    }



    public void updatePromotedInstance(String instance, String category, String newPattern) throws SQLException {
        //can be a problem is multiple entries for the name name is there
        PreparedStatement psRetPromotedInstance = con.prepareStatement("select patterns from promoted_instances where instance = ? and category = ?");
        psRetPromotedInstance.setString(1,instance);
        psRetPromotedInstance.setString(2,category);

        ResultSet rsRetPromotedInstance = psRetPromotedInstance.executeQuery();
        String availablePatterns="";
        while(rsRetPromotedInstance.next()){
            availablePatterns = rsRetPromotedInstance.getString("patterns");
            if(availablePatterns!="")
                availablePatterns+= ", "+newPattern;
            else{
                availablePatterns+=newPattern;
            }
        }

        PreparedStatement psUpdatePromotedInstance = con.prepareStatement("UPDATE promoted_instances SET patterns = ? WHERE instance = ? and category = ?");
        psUpdatePromotedInstance.setString(1,availablePatterns);
        psUpdatePromotedInstance.setString(2,instance);
        psUpdatePromotedInstance.setString(3,category);
        psUpdatePromotedInstance.executeUpdate();
    }


    public LinkedHashMap<String, String> extractInstancesFromPromotedPatterns(int iteration) throws Exception {

        //load promoted patterns
        PreparedStatement psRetrieve = con.prepareStatement("select * from promoted_patterns where PromotedIteration = ?");
        LinkedList<ContextualPattern> patternArrayList = new LinkedList<>();

        for (int i = this.currentIteration - 1; i >= 0; i--) {
            System.out.println("Current Iteration : "+currentIteration+" i = "+i);
            psRetrieve.setInt(1, i);
            ResultSet rst = psRetrieve.executeQuery();
            while (rst.next()) {
                patternArrayList.add(new ContextualPattern(rst.getString("Category"), rst.getString("Pattern")));
            }
        }
        System.out.println("Retrieved "+patternArrayList.size()+" patterns.");
        //create 5 threads, share patterns between them
        ExecutorService threadPool = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 5; i++) {
            threadPool.submit(new PatternMatchRunnable(i, patternArrayList, iteration));
        }

        //for each patterns, search for occurrences
        threadPool.shutdown();
        // wait for the threads to finish if necessary
        threadPool.awaitTermination(10000, TimeUnit.MILLISECONDS);
        return null;
    }


    //    Extract Following pattern
    public ArrayList<String> extractFollowingPattern(String ins, String fname, String lname, ArrayList<String> sentencesList) {

        ArrayList<String> patternList = new ArrayList<>();
        ArrayList<String[]> taggedArray = new ArrayList<>();
        HashMap<String, String> posVsSent = new HashMap<>();
//        Pattern removeBracketcontent = Pattern.compile("\\((.*)\\)\\s");
        Matcher matcher;
//        String pa = "(NN.){1,4}\\s(VB.)\\s(DT|JJ.{0,1}|RB|\\s)*((NN.{0,1}|\\s)*|(IN|\\s){0,1})";
//        String pa = "(NN.{0,1}){1,4}\\s(VB.|NN)\\s(IN|DT|JJ.{0,1}|RB|\\s)*((NN.{0,1}|\\s)*|(IN|\\s){0,1})";
        String pa = "(NN.{0,1}){1,4}\\s(VB.|NN|IN|CC)\\s(IN|DT|JJ.{0,1}|RB|NN.|\\s)*((NN.{0,1}|\\s)*|(IN|\\s){0,1})";
//        String pa = "(NN.{0,1}){1,4}\\s(VB.|NN|IN|CC)\\s(IN|DT|JJ.{0,1}|RB|NN.|\\s)*((NN.{0,1}|\\s)*|(IN|\\s){0,1})";
        Pattern pat = Pattern.compile(pa);
        Matcher m;

        int juncIndex = 0;


            for (String s : sentencesList) {
                s = s.trim();

                if (s.contains(fname))
                    juncIndex = s.indexOf(fname);
                else
                    juncIndex = s.indexOf(lname);
                String followingSub;

                if (juncIndex != -1) {
                    followingSub = s.substring(juncIndex);
                    posVsSent.put(followingSub, cplUtils.getPosSentence(followingSub));
                }
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
            String patternSentence = "";
            for (int i = 0; i < sentW.length; i++) {
                try {
                    if (sentW[i].equals(fname) || sentW[i].equals(lname)) {
                        int cur = i;
                        while (i <= cur + len) {
                            patternSentence += sentW[i] + " ";
                            i++;
                        }
//                    Insert patternSentence in the pattern list
                        patternSentence = patternSentence.trim();
                        String replaceInstancePattern = ins;
//                        if (patternSentence.split(" ").length > ins.split(" ").length) {
                        if (fname != lname) {
                            if (patternSentence.matches(".*" + fname + ".*" + lname + ".*"))
                                replaceInstancePattern = fname + ".*" + lname;
                            else if (patternSentence.contains(fname))
                                replaceInstancePattern = fname;
                            else if (patternSentence.contains(lname))
                                replaceInstancePattern = lname;
                        } else {
                            replaceInstancePattern = fname;
                        }
                        patternSentence = patternSentence.replaceAll(replaceInstancePattern, "argument");

//                        if(patternSentence.replaceAll(",","").matches("(\\w+\\s{0,1})+"))
                        patternList.add(patternSentence);
                    }
                    break;
//                    }
                } catch (ArrayIndexOutOfBoundsException e) {
//                    System.out.println("### " + s + " ### " + fname + " " + lname + " ### " + len);
                }
            }
        }
//        System.out.println(patternList);
        return patternList;
    }

}









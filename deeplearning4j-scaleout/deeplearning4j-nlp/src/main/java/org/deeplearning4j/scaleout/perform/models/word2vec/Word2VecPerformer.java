package org.deeplearning4j.scaleout.perform.models.word2vec;

import org.apache.commons.math3.util.FastMath;
import org.deeplearning4j.models.word2vec.VocabWord;
import org.deeplearning4j.scaleout.api.statetracker.StateTracker;
import org.deeplearning4j.scaleout.conf.Configuration;
import org.deeplearning4j.scaleout.job.Job;
import org.deeplearning4j.scaleout.perform.WorkerPerformer;
import org.deeplearning4j.scaleout.statetracker.hazelcast.HazelCastStateTracker;
import org.deeplearning4j.util.SerializationUtils;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base line word 2 vec performer
 *
 * @author Adam Gibson
 */
public class Word2VecPerformer implements WorkerPerformer {

    private int vectorLength = 50;

    public final static String NAME_SPACE = "org.deeplearning4j.scaleout.perform.models.word2vec";
    public final static String VECTOR_LENGTH = NAME_SPACE + ".length";
    public final static String ADAGRAD = NAME_SPACE + ".adagrad";
    public final static String NEGATIVE = NAME_SPACE + ".negative";
    public final static String NUM_WORDS = NAME_SPACE + ".numwords";
    public final static String TABLE = NAME_SPACE + ".table";
    public final static String WINDOW = NAME_SPACE + ".window";
    public final static String ALPHA = NAME_SPACE + ".alpha";
    public final static String MIN_ALPHA = NAME_SPACE + ".minalpha";
    public final static String TOTAL_WORDS = NAME_SPACE + ".totalwords";
    public final static String NUM_WORDS_SO_FAR = NAME_SPACE + ".wordssofar";

    double[] expTable = new double[1000];
    static double MAX_EXP = 6;
    private boolean useAdaGrad = false;
    private double negative = 5;
    private int numWords = 1;
    private INDArray table;
    private int window = 5;
    private AtomicLong nextRandom = new AtomicLong(5);
    private double alpha = 0.025;
    private double minAlpha = 1e-2;
    private int totalWords = 1;
    private StateTracker stateTracker;
    private static Logger log = LoggerFactory.getLogger(Word2VecPerformer.class);

    @Override
    public void perform(Job job) {

        if(job.getWork() instanceof Word2VecWork) {
            Word2VecWork work = (Word2VecWork) job.getWork();
            List<VocabWord> sentence = work.getSentence();
            trainSentence(sentence,work,this.alpha);
        }
        else if(job.getWork() instanceof Collection) {
            double numWordsSoFar = stateTracker.count(NUM_WORDS_SO_FAR);

            Collection<Word2VecWork> coll = (Collection<Word2VecWork>) job.getWork();
            double alpha2 = Math.max(minAlpha, alpha * (1 - (1.0 *  numWordsSoFar / (double) totalWords)));
            int totalNewWords = 0;
            List<Word2VecResult> deltas = new ArrayList<>();
            for(Word2VecWork work : coll) {
                List<VocabWord> sentence = work.getSentence();
                trainSentence(sentence,work,alpha2);
                totalNewWords += sentence.size();
                deltas.add(work.addDeltas());
            }



            job.setResult((Serializable) deltas);
            stateTracker.increment(NUM_WORDS_SO_FAR,totalNewWords);
        }




    }

    @Override
    public void update(Object... o) {

    }

    @Override
    public void setup(Configuration conf) {
        vectorLength = conf.getInt(VECTOR_LENGTH,50);
        useAdaGrad = conf.getBoolean(ADAGRAD, false);
        negative = conf.getFloat(NEGATIVE, 5);
        numWords = conf.getInt(NUM_WORDS, 1);
        window = conf.getInt(WINDOW, 5);
        alpha = conf.getFloat(ALPHA, 0.025f);
        minAlpha = conf.getFloat(MIN_ALPHA, 1e-2f);
        totalWords = conf.getInt(TOTAL_WORDS,1);

        initExpTable();


        String connectionString = conf.get(STATE_TRACKER_CONNECTION_STRING);


        log.info("Creating state tracker with connection string "+  connectionString);
        if(stateTracker == null)
            try {
                stateTracker = new HazelCastStateTracker(connectionString);
            } catch (Exception e) {
                e.printStackTrace();
            }


        if(negative > 0) {
            ByteArrayInputStream bis = new ByteArrayInputStream(conf.get(TABLE).getBytes());
            table = SerializationUtils.readObject(bis);

        }

    }


    /**
     * Train on a list of vocab words
     * @param sentence the list of vocab words to train on
     */
    public void trainSentence(final List<VocabWord> sentence,Word2VecWork work,double alpha) {
        if(sentence == null || sentence.isEmpty())
            return;
        for(int i = 0; i < sentence.size(); i++) {
            nextRandom.set(nextRandom.get() * 25214903917L + 11);
            skipGram(i, sentence, (int) nextRandom.get() % window,work,alpha);
        }





    }


    /**
     * Train via skip gram
     * @param i
     * @param sentence
     */
    public void skipGram(int i,List<VocabWord> sentence, int b,Word2VecWork work,double alpha) {

        final VocabWord word = sentence.get(i);
        if(word == null || sentence.isEmpty())
            return;

        int end =  window * 2 + 1 - b;
        for(int a = b; a < end; a++) {
            if(a != window) {
                int c = i - window + a;
                if(c >= 0 && c < sentence.size()) {
                    VocabWord lastWord = sentence.get(c);
                    iterateSample(work, word, lastWord,alpha);
                }
            }
        }




    }



    /**
     * Iterate on the given 2 vocab words
     *
     * @param w1 the first word to iterate on
     * @param w2 the second word to iterate on
     */
    public  void iterateSample(Word2VecWork work,VocabWord w1, VocabWord w2,double alpha) {
        if(w2 == null || w2.getIndex() < 0)
            return;

        //current word vector
        INDArray l1 = work.getVectors().get(w2.getWord()).getSecond();


        //error for current word and context
        INDArray neu1e = Nd4j.create(vectorLength);


        double avgChange = 0.0f;




        for(int i = 0; i < w1.getCodeLength(); i++) {
            int code = w1.getCodes().get(i);
            int point = w1.getPoints().get(i);

            //other word vector

            INDArray syn1 = work.getSyn1Vectors().get(work.getIndexes().get(point).getWord());


            double dot = Nd4j.getBlasWrapper().dot(l1,syn1);

            if(dot < -MAX_EXP || dot >= MAX_EXP)
                continue;


            int idx = (int) ((dot + MAX_EXP) * ((double) expTable.length / MAX_EXP / 2.0));
            if(idx >= expTable.length)
                continue;

            //score
            double f =  expTable[idx];
            //gradient
            double g = (1 - code - f) * (useAdaGrad ?  w1.getLearningRate(i,alpha) : alpha);




            avgChange += g;
            if(neu1e.data().dataType() == DataBuffer.DOUBLE) {
                Nd4j.getBlasWrapper().axpy(g, syn1, neu1e);
                Nd4j.getBlasWrapper().axpy(g, l1, syn1);
            }
            else {
                Nd4j.getBlasWrapper().axpy((float) g, syn1, neu1e);
                Nd4j.getBlasWrapper().axpy((float) g, l1, syn1);
            }


        }


        int target = w1.getIndex();
        int label;
        INDArray syn1Neg = work.getNegativeVectors().get(work.getIndexes().get(target).getWord()).getSecond();
        //negative sampling
        if(negative > 0)
            for (int d = 0; d < negative + 1; d++) {
                if (d == 0) {

                    label = 1;
                } else {
                    nextRandom.set(nextRandom.get() * 25214903917L + 11);
                    target = table.getInt((int) (nextRandom.get() >> 16) % table.length());
                    if (target == 0)
                        target = (int) nextRandom.get() % (numWords - 1) + 1;
                    if (target == w1.getIndex())
                        continue;
                    label = 0;
                }

                double f = Nd4j.getBlasWrapper().dot(l1,syn1Neg);
                double g;
                if (f > MAX_EXP)
                    g = (label - 1) * (useAdaGrad ?  w1.getLearningRate(target,alpha) : alpha);
                else if (f < -MAX_EXP)
                    g = (label - 0) * (useAdaGrad ?  w1.getLearningRate(target,alpha) : alpha);
                else
                    g = (label - expTable[(int)((f + MAX_EXP) * (expTable.length / MAX_EXP / 2))]) *  (useAdaGrad ?  w1.getLearningRate(target,alpha) : alpha);
                if(neu1e.data().dataType() == DataBuffer.DOUBLE)
                    Nd4j.getBlasWrapper().axpy(g,neu1e,l1);
                else
                    Nd4j.getBlasWrapper().axpy((float) g,neu1e,l1);

                if(neu1e.data().dataType() == DataBuffer.DOUBLE)
                    Nd4j.getBlasWrapper().axpy(g,syn1Neg,l1);
                else
                    Nd4j.getBlasWrapper().axpy((float) g,syn1Neg,l1);
            }


        avgChange /=  w1.getCodes().size();


        if(useAdaGrad) {
            if(neu1e.data().dataType() == DataBuffer.DOUBLE)
                Nd4j.getBlasWrapper().axpy(avgChange,neu1e,l1);
            else
                Nd4j.getBlasWrapper().axpy((float) avgChange,neu1e,l1);


        }
        else {
            if(neu1e.data().dataType() == DataBuffer.DOUBLE)
                Nd4j.getBlasWrapper().axpy(1.0,neu1e,l1);

            else
                Nd4j.getBlasWrapper().axpy(1.0f,neu1e,l1);

        }




    }




    private void initExpTable() {
        for (int i = 0; i < expTable.length; i++) {
            double tmp =   FastMath.exp((i / (double) expTable.length * 2 - 1) * MAX_EXP);
            expTable[i]  = tmp / (tmp + 1.0);
        }
    }




}
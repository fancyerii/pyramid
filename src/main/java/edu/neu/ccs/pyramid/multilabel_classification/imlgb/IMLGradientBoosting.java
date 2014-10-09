package edu.neu.ccs.pyramid.multilabel_classification.imlgb;

import edu.neu.ccs.pyramid.dataset.FeatureRow;
import edu.neu.ccs.pyramid.dataset.MultiLabel;
import edu.neu.ccs.pyramid.dataset.MultiLabelClfDataSet;
import edu.neu.ccs.pyramid.multilabel_classification.MLPriorProbClassifier;
import edu.neu.ccs.pyramid.multilabel_classification.MultiLabelClassifier;
import edu.neu.ccs.pyramid.regression.ConstantRegressor;
import edu.neu.ccs.pyramid.regression.Regressor;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * gradient boosting for independent labels
 * Created by chengli on 10/8/14.
 */
public class IMLGradientBoosting implements MultiLabelClassifier{
    private static final long serialVersionUID = 1L;
    private List<List<Regressor>> regressors;
    private int numClasses;
    /**
     * legal assignments of labels
     */
    private transient IMLGBTrainer trainer;

    public IMLGradientBoosting(int numClasses) {
        this.numClasses = numClasses;
        this.regressors = new ArrayList<>(this.numClasses);
        for (int k=0;k<this.numClasses;k++){
            List<Regressor> regressorsClassK  = new ArrayList<>();
            this.regressors.add(regressorsClassK);
        }
    }

    /**
     * not sure whether this is good for performance
     * start with prior probabilities
     * should be called before setTrainConfig
     * @param probs
     */
    public void setPriorProbs(double[] probs){
        if (probs.length!=this.numClasses){
            throw new IllegalArgumentException("probs.length!=this.numClasses");
        }
        double average = Arrays.stream(probs).map(Math::log).average().getAsDouble();
        for (int k=0;k<this.numClasses;k++){
            double score = Math.log(probs[k] - average);
            Regressor constant = new ConstantRegressor(score);
            this.addRegressor(constant, k);
        }
    }

    /**
     * not sure whether this is good for performance
     * start with prior probabilities
     * should be called before setTrainConfig
     */
    public void setPriorProbs(MultiLabelClfDataSet dataSet){
        MLPriorProbClassifier priorProbClassifier = new MLPriorProbClassifier(this.numClasses);
        priorProbClassifier.fit(dataSet);
        double[] probs = priorProbClassifier.getClassProbs();
        this.setPriorProbs(probs);
    }


    /**
     * to start/resume training, set train config
     * @param config
     */
    public void setTrainConfig(IMLGBConfig config) {
        if (config.getDataSet().getNumClasses()!=this.numClasses){
            throw new RuntimeException("number of classes given in the config does not match number of classes in boosting");
        }
        this.trainer = new IMLGBTrainer(config,this.regressors);
    }

    /**
     * default boosting method should follow this order
     * @throws Exception
     */
    public void boostOneRound()  {
        if (this.trainer==null){
            throw new RuntimeException("set train config first");
        }
        this.calGradients();
        //for non-standard experiments
        //we can do something here
        //for example, we can add more columns and call setActiveFeatures() here
        this.fitRegressors();
    }

    /**
     * for external usage
     * @param k
     * @return
     */
    public double[] getGradients(int k){
        return this.trainer.getGradients(k);
    }

    /**
     * parallel by class
     */
    public void calGradients(){
        this.trainer.updateClassGradientMatrix();
    }

    public void fitRegressors(){
        for (int k=0;k<this.numClasses;k++){
            /**
             * parallel by feature
             */
            Regressor regressor = this.trainer.fitClassK(k);
            this.addRegressor(regressor, k);
            /**
             * parallel by data
             */
            this.trainer.updateStagedClassScores(regressor,k);
        }
    }

    void addRegressor(Regressor regressor, int k){
        this.regressors.get(k).add(regressor);
    }

    /**
     * reset activeDataPoints for later rounds
     * @param activeDataPoints
     */
    public void setActiveDataPoints(int[] activeDataPoints){
        this.trainer.setActiveDataPoints(activeDataPoints);
    }

    /**
     * reset activeFeatures for later rounds
     * @param activeFeatures
     */
    public void setActiveFeatures(int[] activeFeatures){
        this.trainer.setActiveFeatures(activeFeatures);
    }

    public MultiLabel predict(FeatureRow featureRow){
        MultiLabel prediction = new MultiLabel(this.numClasses);
        for (int k=0;k<numClasses;k++){
            double score = this.calClassScore(featureRow,k);
            if (score > 0){
                prediction.addLabel(k);
            }
        }
        return prediction;
    }


    /**
     *
     * @param featureRow
     * @param k class index
     * @return
     */
    public double calClassScore(FeatureRow featureRow, int k){
        List<Regressor> regressorsClassK = this.regressors.get(k);
        double score = 0;
        for (Regressor regressor: regressorsClassK){
            score += regressor.predict(featureRow);
        }
        return score;
    }


    public List<Regressor> getRegressors(int k){
        return this.regressors.get(k);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int k=0;k<this.numClasses;k++){
            sb.append("for class ").append(k).append("\n");
            List<Regressor> trees = this.getRegressors(k);
            for (int i=0;i<trees.size();i++){
                sb.append("tree ").append(i).append(":");
                sb.append(trees.get(i).toString());
            }
        }
        return sb.toString();
    }



    public static IMLGradientBoosting deserialize(String file) throws Exception{
        return deserialize(new File(file));
    }

    /**
     * de-serialize from file
     * @param file
     * @return
     * @throws Exception
     */
    public static IMLGradientBoosting deserialize(File file) throws Exception{
        try(
                FileInputStream fileInputStream = new FileInputStream(file);
                BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
                ObjectInputStream objectInputStream = new ObjectInputStream(bufferedInputStream);
        ){
            IMLGradientBoosting boosting = (IMLGradientBoosting)objectInputStream.readObject();
            return boosting;
        }
    }

}
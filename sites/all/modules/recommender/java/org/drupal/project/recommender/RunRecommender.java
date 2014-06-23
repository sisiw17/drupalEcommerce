package org.drupal.project.recommender;

import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.mahout.cf.taste.impl.common.LongPrimitiveArrayIterator;
import org.apache.mahout.cf.taste.impl.common.LongPrimitiveIterator;
import org.apache.mahout.cf.taste.impl.model.file.FileDataModel;
import org.apache.mahout.cf.taste.impl.model.jdbc.*;
import org.apache.mahout.cf.taste.impl.neighborhood.NearestNUserNeighborhood;
import org.apache.mahout.cf.taste.impl.recommender.GenericBooleanPrefItemBasedRecommender;
import org.apache.mahout.cf.taste.impl.recommender.GenericBooleanPrefUserBasedRecommender;
import org.apache.mahout.cf.taste.impl.recommender.GenericItemBasedRecommender;
import org.apache.mahout.cf.taste.impl.recommender.GenericUserBasedRecommender;
import org.apache.mahout.cf.taste.impl.recommender.svd.ExpectationMaximizationSVDFactorizer;
import org.apache.mahout.cf.taste.impl.recommender.svd.Factorizer;
import org.apache.mahout.cf.taste.impl.recommender.svd.SVDRecommender;
import org.apache.mahout.cf.taste.impl.similarity.*;
import org.apache.mahout.cf.taste.model.DataModel;
import org.apache.mahout.cf.taste.model.JDBCDataModel;
import org.apache.mahout.cf.taste.recommender.ItemBasedRecommender;
import org.apache.mahout.cf.taste.recommender.RecommendedItem;
import org.apache.mahout.cf.taste.recommender.Recommender;
import org.apache.mahout.cf.taste.recommender.UserBasedRecommender;
import org.apache.mahout.cf.taste.similarity.ItemSimilarity;
import org.apache.mahout.cf.taste.similarity.UserSimilarity;
import org.drupal.project.async_command.*;
import org.drupal.project.async_command.exception.ConfigLoadingException;
import org.drupal.project.async_command.exception.DatabaseRuntimeException;
import org.drupal.project.async_command.exception.DrupletException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * This is the main computation of Recommender
 *
 * TODO list for refactor:
 * - AlgorithmImpl is ugly. For each algorithm, use different class
 * - File recommender is a hack. Use the OO way.
 * - genericComputeSave() is too complicated.
 * - remove sequential execution and use Thread alone.
 * - architectural support for Hadoop
 */
public class RunRecommender extends AsyncCommand {

    @Deprecated
    public static RunRecommender create(long appId, Druplet druplet) {
        return create(appId, null, druplet);
    }

    @Deprecated
    public static RunRecommender create(long appId, String prefFilename, Druplet druplet) {
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("id1", appId);
        if (prefFilename != null) {
            fields.put("string1", prefFilename);
        }
        CommandRecord record = CommandRecord.forge(fields);
        return new RunRecommender(record, druplet);
    }


    /**
     * Prepare recommender using info from 'record'
     *
     * @param record Record from database table.
     * @param druplet
     */
    public RunRecommender(CommandRecord record, Druplet druplet) {
        super(record, druplet);
        drupalConnection = druplet.getDrupalConnection();
        prepareCommand();
    }


    // TODO: allow setting this parameters from config.properties
    /**
     * How many similarity/prediction records to keep for each item.
     */
    private final int DEFAULT_MAX_KEEP = 100;

    /**
     * when numbers of users/items lower than this number, load into memory first in "auto" mode.
     */
    private final int LOAD_MEMORY_THRESHOLD = 1000000;

    /**
     * how many records processed to show progress info in genericComputeSave()
     */
    private final int PROGRESS_INCREMENT = 500;

    protected enum AlgorithmType {
        ITEM2ITEM, ITEM2ITEM_INCREMENT, USER2USER, SVD
    }

    // unless you understand what similarity is useful, just choose AUTO and delegate to the algorithm to decide.
    protected enum SimilarityType {
        AUTO, CITYBLOCK, EUCLIDEAN, LOGLIKELIHOOD, PEARSON, SPEARMAN, TANIMOTO, COSINE
    }

    protected enum PerformanceType {
        MEMORY, DATABASE, AUTO
    }

    enum PreferenceType {
        SCORE, BOOLEAN
    }

    int appID;
    int appBaseID;
    AlgorithmType appAlgorithmType;
    AlgorithmImpl appAlgorithmImpl;
    String appTable;
    String appViewName;
    List<String> appFields;

    String appFieldUser;
    String appFieldItem;
    String appFieldPreference;
    String appFieldTimestamp;

    // this specifies whether to use database view or recommender_preference_staging table.
    boolean appStagingUseView;
    // this specifies whether to use file as input (preferences) and output (similarity/predictions)
    boolean appDataUseFile;

    String appPreferenceFilename;
    String appSimilarityFilename;
    String appPredictionFilename;

    SimilarityType appSimilarityType;
    PerformanceType appPerformanceType;
    PreferenceType appPreferenceType;
    int appMaxKeep;
    
    String appPredictionSourceFilter;
    String appPredictionScoreFilter;
    String appSimilarityScoreFilter;

    long updatedTimestamp; // current timestamp when this algorithm runs.
    int numUsers;
    int numItems;
    int numSimilarity;
    int numPrediction;


    protected DrupalConnection drupalConnection;

//        * extra parameters to consider:
//        *
//        *   'missing' => how to handle missing data
//        *         'none' do nothing
//        *         'zero' fill in missing data with zero
//        *         'adjusted' skip mice that don't share cheese in common.
//        *   'sensitivity' => float. if similarity is smaller enough to be less than a certain value (sensitivity), we just discard those
//        *   'lowerbound' => float. if similarity is smaller enough to be less than this value, we just discard those
//        *   'duplicate' => how to handle predictions that already exists in mouse-cheese evaluation.
//        *          'keep'
//        *          'remove'
//        *   'incremental' => whether to rebuild the whole similarity matrix, or incrementally update those got changed.
//        *          'rebuild',
//        *          'refresh'
//        *
//        * for slopeone:
//        *   'extention' => whether to use 'basic', 'weighted', or 'bipolar' extensions of the algorithm. Please refer to the original research paper. Usually it could just be 'weighted'.


    private void prepareCommand() {
        appID = (int) record.getId1().longValue();
        // when appDataUseFile is set, then this points to the input preference file. could be null.
        appPreferenceFilename = record.getString1();
        updatedTimestamp = DrupletUtils.getLocalUnixTimestamp();
        String paramsStr;
        try {
            if (appID == 0) {
                // attention: if appID is 0, then string3 should store the recommender parameters.
                // Only works for file recommender because for database recommender we need appID to store predictions/similarities anyway.
                paramsStr = record.getString3();
            } else {
                paramsStr = (String) drupalConnection.queryValue("SELECT params FROM {recommender_app} WHERE id=?", appID);
            }

            if (paramsStr == null) {
                throw new ConfigLoadingException("Cannot find recommender parameters. Fatal error.");
            }
 		    Map<String, Object> appParams = DrupletUtils.decodeJsonObject(paramsStr);
            parseAppParams(appParams);
        } catch (SQLException e) {
            throw new DatabaseRuntimeException(e);
        } catch (ClassCastException e) {
            throw new ConfigLoadingException(e);
        }
    }

    /**
     * If the input is a SQL query, then staging the data into {recommender_preference_staging} first.
     * Or, create a database view if "staging" is set to view.
     */
    private void processTable() throws SQLException {
        if (appDataUseFile) {
            return; // if we use input file, then do nothing here.
        }

        if (appTable.matches("\\{.*\\}")) {
            appTable = drupalConnection.d(appTable);  // escape the table.
            return;  // that means the app use existing table, and no need to process staging table.
        }

        if (!appTable.trim().toUpperCase().startsWith("SELECT")) {
            throw new ConfigLoadingException("The SQL query should starts with SELECT");
        }

        if (appStagingUseView) {
            try {
                drupalConnection.update("CREATE VIEW " + appViewName + " AS " + appTable);
                logger.info("Using database view " + appViewName + " as preference table.");
                appTable = drupalConnection.d(appViewName);
                return;
            } catch (Throwable e) {
                logger.warning("Cannot create view for appID: " + appID + ". Fall back to use recommender_preference_staging. Please set 'staging'='table'");
            }
        }

        logger.info("Using {recommender_preference_staging} table. Loading data.");

        // clean the table.
        drupalConnection.update("DELETE FROM {recommender_preference_staging}");

        List<Map<String, Object>> results = drupalConnection.query(appTable);
        Object[][] values = new Object[results.size()][4];
        int count = 0;
        for (Map<String, Object> row : results) {
            values[count][0] = row.get(appFieldUser);
            values[count][1] = row.get(appFieldItem);
            values[count][2] = appFieldPreference != null ? row.get(appFieldPreference) : null;
            values[count][3] = appFieldTimestamp != null ? row.get(appFieldTimestamp) : updatedTimestamp;
            count ++;
        }

        // TODO: we can save some insert data in "increment" mode. Also use batch uploader.
        drupalConnection.batch("INSERT INTO {recommender_preference_staging}(source_eid, target_eid, score, updated) VALUES(?, ?, ?, ?)", values);
        //appSql = null;  // could save this variable if needed.
        appTable = drupalConnection.d("{recommender_preference_staging}");
        appFieldUser = "source_eid";
        appFieldItem = "target_eid";
        appFieldPreference = "score";
        appFieldTimestamp = "updated";
    }


    /**
     * This is the method that parse all recommender app parameters defined in recommender_app_register().
     * @param appParams The 'params' field defined in recommender app.
     * @throws SQLException
     */
    private void parseAppParams(Map<String, Object> appParams) throws SQLException {
        parseAlgorithmType(appParams);
        parseDBTable(appParams);
        parseOptional(appParams);
    }

    private void parseOptional(Map<String, Object> appParams) throws SQLException {
        if (appParams.containsKey("similarity")) {
            appSimilarityType = SimilarityType.valueOf(((String) appParams.get("similarity")).toUpperCase());
        }

        appPerformanceType = appParams.containsKey("performance") ?
                PerformanceType.valueOf(((String) appParams.get("performance")).toUpperCase()) : PerformanceType.AUTO;

        appPreferenceType = appParams.containsKey("preference") ?
                PreferenceType.valueOf(((String) appParams.get("preference")).toUpperCase()) : PreferenceType.BOOLEAN;
        if (appPreferenceType == PreferenceType.SCORE && appFieldPreference == null) {
            throw new ConfigLoadingException("Please specify preference field if score is used.");
        }

        appMaxKeep = appParams.containsKey("max_keep") ? (Integer) appParams.get("max_keep") : DEFAULT_MAX_KEEP;

        // parameters needed for incremental algorithms.
        if (appParams.containsKey("base_app_name")) {
            Object id = drupalConnection.queryValue("SELECT id FROM {recommender_app} WHERE name=?", appParams.get("base_app_name"));
            if (id != null) {
                appBaseID = ((Long)id).intValue();
            }
        }

        if (appParams.containsKey("prediction_source_filter")) {
            appPredictionSourceFilter = (String) appParams.get("prediction_source_filter");
            if (!appPredictionSourceFilter.matches("<\\d+")) {
                logger.warning("Currently the program only supports prediction_source_filter <N");
                appPredictionSourceFilter = null;
            }
        }

        if (appParams.containsKey("prediction_score_filter")) {
            appPredictionScoreFilter = (String) appParams.get("prediction_score_filter");
            if (!appPredictionScoreFilter.matches(">[0-9.]+")) {
                logger.warning("Currently the program only supports prediction_score_filter >x");
                appPredictionScoreFilter = null;
            }
        }
        
        if (appParams.containsKey("similarity_score_filter")) {
            appSimilarityScoreFilter = (String) appParams.get("similarity_score_filter");
            if (!appSimilarityScoreFilter.matches(">[0-9.]+")) {
                logger.warning("Currently the program only supports similarity_score_filter >x");
                appSimilarityScoreFilter = null;
            }
        }
    }


    private void parseDBTable(Map<String, Object> appParams) {
        appDataUseFile = false;
        if (!appParams.containsKey("table")) {
            throw new ConfigLoadingException("You have to provide a SQL query, a table name, <FILE>, or <BUILTIN> for the user-item preference.");
        } else {
            appTable = (String) appParams.get("table");
        }

        if (appTable.equals("<BUILTIN>")) {
            appTable = "SELECT source_eid, target_eid, score, updated FROM {recommender_preference} WHERE app_id=" + appID;
            appFieldUser = "source_eid";
            appFieldItem = "target_eid";
            appFieldPreference = "score";
            appFieldTimestamp = "updated";
            
            
        } else if (appTable.equals("<FILE>")) {
            appDataUseFile = true;
            if (appPreferenceFilename == null) {
                if (appParams.containsKey("preference_file")) {
                    appPreferenceFilename = (String) appParams.get("preference_file");
                }
            }
            if (appPreferenceFilename == null || !(new File(appPreferenceFilename)).exists()) {
                throw new ConfigLoadingException("Please specify a valid preference file name.");
            }
            if (appPreferenceFilename.endsWith(".pref")) {
                // allow removing .pref suffix.
                String baseName = appPreferenceFilename.substring(0, appPreferenceFilename.length()-5);
                appSimilarityFilename = baseName + ".simi";
                appPredictionFilename = baseName + ".pred";
            } else {
                appSimilarityFilename = appPreferenceFilename + ".simi";
                appPredictionFilename = appPreferenceFilename + ".pred";
            }
            
            // set appTable to be null because here we use data file.
            appTable = null;
            // attention: use fake field names. could change them to use the csv fields.
            appFieldUser = "source_eid";
            appFieldItem = "target_eid";
            appFieldPreference = "score";
            appFieldTimestamp = "updated";
            
        } else {
            appFields = new ArrayList<String>((List) appParams.get("fields"));
            // description of the fields see: org.apache.mahout.cf.taste.impl.model.file.FileDataModel
            switch (appFields.size()) {
                case 4:
                    //appFieldTimestamp = appFields.get(3) == SerializedPhpParser.NULL ? null : appFields.get(3);
                    appFieldTimestamp = appFields.get(3); // TODO: verify NULL is the same null.
                case 3:
                    //appFieldPreference = appFields.get(2) == SerializedPhpParser.NULL ? null : appFields.get(2);
                    appFieldPreference = appFields.get(2);
                case 2:
                    appFieldUser = appFields.get(0);
                    appFieldItem = appFields.get(1);
                    break;
                default:
                    throw new ConfigLoadingException("Wrong parameters in recommender->app->fields");
            }
        }

        // FIXME: check view usage.
        if (appParams.containsKey("staging") && ((String) appParams.get("staging")).trim().toLowerCase().equals("view")) {
            appStagingUseView = true;
            appViewName = "{recommender_preference_" + appID + "}";
        } else {
            appStagingUseView = false;
        }
    }


    private void parseAlgorithmType(Map<String, Object> appParams) {
        appAlgorithmType = AlgorithmType.valueOf(((String) appParams.get("algorithm")).toUpperCase());

        switch (appAlgorithmType) {
            case ITEM2ITEM:
                appAlgorithmImpl = new Item2Item();
                break;
            case ITEM2ITEM_INCREMENT:
                appAlgorithmImpl = new Item2ItemIncrement();
                break;
            case USER2USER:
                appAlgorithmImpl = new User2User();
                break;
            case SVD:
                appAlgorithmImpl = new SVD();
                break;
            default:
                throw new ConfigLoadingException("Internal error: Unimplemented algorithm.");
        }
    }

    /**
     * The parameter should be set in config.properties rather than recommenderAppParam.
     * TODO: implement RecommenderConfig
     *
     * @return The threads number to use when computing recommendations. 0 means no multithreading is supported
     */
    private int getThreadsNumber() {
        int num = 0;
        try {
            num = druplet.getDrupletConfig().getIntProperty("recommender_computing_threads", 0);
        } catch (Throwable e) {
            logger.info("Could not read recommender_computing_threads. Use single thread instead.");
        }
        return num;
    }


    @Override
    public void run() {
        record.setStart(DrupletUtils.getLocalUnixTimestamp());
        try {
            appAlgorithmImpl.run();
            record.setStatus(Status.SUCCESS);
            record.setMessage(appAlgorithmImpl.getMessage());
            record.setEnd(DrupletUtils.getLocalUnixTimestamp());
            record.setNumber1((float) numUsers);
            record.setNumber2((float) numItems);
            record.setNumber3((float) numSimilarity);
            record.setNumber4((float) numPrediction);
            if (appStagingUseView) {
                try {
                    drupalConnection.update("DROP VIEW " + appViewName);
                } catch (Throwable e) {
                    logger.warning("Cannot drop view " + appViewName + ". Please set staging=table instead.");
                }
            }
            if (appDataUseFile) {
                record.setString2(appSimilarityFilename);
                record.setString3(appPredictionFilename);
            }
            // FIXME: if error, should set the error message rather than throw unhandled exceptions.
        } catch (SQLException e) {
            throw new DatabaseRuntimeException(e);
        } catch (TasteException e) {
            throw new DrupletException(e);
        } catch (IOException e) {
            throw new DrupletException(e);
        }
    }



    /**
     * This class is written with Item-base recommender in mind.
     */
    class AlgorithmImpl {

        protected DataModel dataModel;
        protected ItemSimilarity itemSimilarity;
        protected UserSimilarity userSimilarity;
        protected Recommender recommender;
        protected StringBuilder message = new StringBuilder();


        private class RecommenderCallable implements Callable<Long> {
            private long entityID1;
            private boolean rebuild;
            private BatchUploader deleteBatch;
            private BatchUploader insertBatch;
            private EntityHandler handler;

            public RecommenderCallable(long entityID1, boolean rebuild, BatchUploader deleteBatch, BatchUploader insertBatch, EntityHandler handler) {
                this.entityID1 = entityID1;
                this.rebuild = rebuild;
                this.deleteBatch = deleteBatch;
                this.insertBatch = insertBatch;
                this.handler = handler;
            }

            @Override
            public Long call() {
                logger.finest("Computing for entity: " + entityID1);
                long resultCount = 0;
                if (!rebuild) {
                    deleteBatch.put(appID, entityID1, updatedTimestamp);
                }
                try {
                    for (RecommendedEntity entity : handler.getRecommendedEntity(entityID1)) {
                        long entityID2 = entity.getEntityID();
                        double score = entity.getValue();
                        if (!handler.validateTarget(entityID2) || !handler.validateScore(score)) {
                            continue;
                        }

                        insertBatch.put(appID, entityID1, entityID2, score, updatedTimestamp);
                        resultCount ++;
                    }
                } catch (TasteException e) {
                    logger.warning("Cannot compute recommendation for entity: " + entityID1);
                }
                return resultCount;
            }
        }


        private class RecommenderUseFileCallable implements Callable<Long> {
            private final long entityID1;
            private final BufferedWriter writer;
            private final EntityHandler handler;

            public RecommenderUseFileCallable(long entityID1, BufferedWriter writer, EntityHandler handler) {
                this.entityID1 = entityID1;
                this.writer = writer;
                this.handler = handler;
            }

            @Override
            public Long call() {
                logger.finest("Computing for entity: " + entityID1);
                long resultCount = 0;
                try {
                    for (RecommendedEntity entity : handler.getRecommendedEntity(entityID1)) {
                        long entityID2 = entity.getEntityID();
                        double score = entity.getValue();
                        if (!handler.validateTarget(entityID2) || !handler.validateScore(score)) {
                            continue;
                        }

                        synchronized (writer) {
                            writer.write("" + entityID1 + ',' + entityID2 + ',' + score + ',' + updatedTimestamp + "\n");
                        }
                        resultCount ++;
                    }
                } catch (TasteException e) {
                    logger.warning("Cannot compute recommendation for entity: " + entityID1);
                } catch (IOException e) {
                    e.printStackTrace();
                    logger.severe("IO error when computing ID: " + entityID1);
                }
                return resultCount;
            }
        }

        protected class RecommendedEntity {
            private long entityID;
            private float value;
            public RecommendedEntity(long entityID, float value) {
                this.entityID = entityID;
                this.value = value;
            }
            public long getEntityID() {
                return entityID;
            }
            public float getValue() {
                return value;
            }
        }


        protected abstract class EntityHandler {
            public abstract List<RecommendedEntity> getRecommendedEntity(long entityID) throws TasteException;
            public List<RecommendedEntity> wrap(List<RecommendedItem> original) {
                List<RecommendedEntity> wrapped = new ArrayList<RecommendedEntity>();
                for (RecommendedItem item : original) {
                    wrapped.add(new RecommendedEntity(item.getItemID(), item.getValue()));
                }
                return wrapped;
            }
            public boolean validateSource(long entityID) {
                return true;  // True if input entity is accepted
            }
            public boolean validateTarget(long entityID) {
                return true;
            }
            public boolean validateScore(double score) {
                return true;
            }
            protected boolean validateSimilarityScore(double score) {
                if (appSimilarityScoreFilter != null && appSimilarityScoreFilter.startsWith(">")) {
                    double min = Double.parseDouble(appSimilarityScoreFilter.substring(1));
                    return score > min;
                } else {
                    return true;
                }
            }
        }

        protected EntityHandler defaultItemSimilarityHandler = new EntityHandler() {
            @Override
            public List<RecommendedEntity> getRecommendedEntity(long entityID) throws TasteException {
                return wrap(((ItemBasedRecommender) recommender).mostSimilarItems(entityID, appMaxKeep));
            }
            @Override
            public boolean validateScore(double score) {
                return validateSimilarityScore(score);
            }
        };

        protected EntityHandler defaultUserSimilarityHandler = new EntityHandler() {
            @Override
            public List<RecommendedEntity> getRecommendedEntity(long entityID) throws TasteException {
                List<RecommendedEntity> recommended = new ArrayList<RecommendedEntity>();
                long[] similarUserIDs = ((UserBasedRecommender) recommender).mostSimilarUserIDs(entityID, appMaxKeep);
                for (long userID : similarUserIDs) {
                    recommended.add(new RecommendedEntity(userID, new Float(userSimilarity.userSimilarity(entityID, userID))));
                }
                return recommended;
            }
            @Override
            public boolean validateScore(double score) {
                return validateSimilarityScore(score);
            }
        };

        protected EntityHandler defaultPredictionHandler = new EntityHandler() {
            @Override
            public List<RecommendedEntity> getRecommendedEntity(long entityID) throws TasteException {
                return wrap( recommender.recommend(entityID, appMaxKeep) );
            }
            @Override
            public boolean validateSource(long entityID) {
                if (appPredictionSourceFilter != null && appPredictionSourceFilter.startsWith("<")) {
                    long watermark = Long.parseLong(appPredictionSourceFilter.substring(1));
                    return entityID < watermark;
                } else {
                    return true;
                }
            }
            @Override
            public boolean validateScore(double score) {
                if (appPredictionScoreFilter != null && appPredictionScoreFilter.startsWith(">")) {
                    double min = Double.parseDouble(appPredictionScoreFilter.substring(1));
                    return score > min;
                } else {
                    return true;
                }
            }
        };

        public String getMessage() {
            return message.toString();
        }


        protected void initDataModel() throws TasteException {
            logger.info("Initializing data model.");

            if (appDataUseFile) {
                try {
                    dataModel = new FileDataModel(new File(appPreferenceFilename));
                } catch (IOException e) {
                    throw new DrupletException("Cannot read preference file.", e);
                }
                return;
            }

            // attention: we do the wrap here. but in fact we already used DBCP for pooling.
            ConnectionPoolDataSource wrapperDataSource = new ConnectionPoolDataSource(drupalConnection.getDataSource());
            if (drupalConnection.getDatabaseType().equals(DrupalConnection.DatabaseType.MYSQL) && appPreferenceType == PreferenceType.BOOLEAN) {
                dataModel = new MySQLBooleanPrefJDBCDataModel(wrapperDataSource, appTable, appFieldUser, appFieldItem, appFieldTimestamp);
            }
            else if (drupalConnection.getDatabaseType().equals(DrupalConnection.DatabaseType.MYSQL) && appPreferenceType == PreferenceType.SCORE) {
                dataModel = new MySQLJDBCDataModel(wrapperDataSource, appTable, appFieldUser, appFieldItem, appFieldPreference, appFieldTimestamp);
            }
            else if (drupalConnection.getDatabaseType().equals(DrupalConnection.DatabaseType.POSTGRESQL) && appPreferenceType == PreferenceType.BOOLEAN) {
                dataModel = new PostgreSQLBooleanPrefJDBCDataModel(wrapperDataSource, appTable, appFieldUser, appFieldItem, appFieldTimestamp);
            }
            else if (drupalConnection.getDatabaseType().equals(DrupalConnection.DatabaseType.POSTGRESQL) && appPreferenceType == PreferenceType.SCORE) {
                dataModel = new PostgreSQLJDBCDataModel(wrapperDataSource, appTable, appFieldUser, appFieldItem, appFieldPreference, appFieldTimestamp);
            }
            else if (appPreferenceType == PreferenceType.BOOLEAN) {
                dataModel = new SQL92BooleanPrefJDBCDataModel(wrapperDataSource, appTable, appFieldUser, appFieldItem, appFieldTimestamp);
            }
            else if (appPreferenceType == PreferenceType.SCORE) {
                dataModel = new SQL92JDBCDataModel(wrapperDataSource, appTable, appFieldUser, appFieldItem, appFieldPreference, appFieldTimestamp);
            }
            else {
                assert false;
            }

            if (appPerformanceType == null || appPerformanceType == PerformanceType.AUTO) {
                if (dataModel.getNumUsers() < LOAD_MEMORY_THRESHOLD && dataModel.getNumItems() < LOAD_MEMORY_THRESHOLD) {
                    appPerformanceType = PerformanceType.MEMORY;
                }
            }

            if (appPerformanceType == PerformanceType.MEMORY) {
                logger.info("Switching to MEMORY mode. Load all data from database into memory first.");
                dataModel = new ReloadFromJDBCDataModel((JDBCDataModel) dataModel);
            }
        }


        protected void initSimilarity() throws TasteException {
            if (appSimilarityType == null || appSimilarityType == SimilarityType.AUTO) {
                // automatically set common similarity model based on whether it's boolean or score preference.
                if (appPreferenceType == PreferenceType.BOOLEAN) {
                    itemSimilarity = new LogLikelihoodSimilarity(dataModel);
                } else if (appPreferenceType == PreferenceType.SCORE) {
                    itemSimilarity = new PearsonCorrelationSimilarity(dataModel);
                } else {
                    assert false;
                }
            } else if (appSimilarityType == SimilarityType.CITYBLOCK) {
                itemSimilarity = new CityBlockSimilarity(dataModel);
            } else if (appSimilarityType == SimilarityType.EUCLIDEAN) {
                itemSimilarity = new EuclideanDistanceSimilarity(dataModel);
            } else if (appSimilarityType == SimilarityType.LOGLIKELIHOOD) {
                itemSimilarity = new LogLikelihoodSimilarity(dataModel);
            } else if (appSimilarityType == SimilarityType.PEARSON) {
                itemSimilarity = new PearsonCorrelationSimilarity(dataModel);
            } else if (appSimilarityType == SimilarityType.TANIMOTO) {
                itemSimilarity = new TanimotoCoefficientSimilarity(dataModel);
            } else if (appSimilarityType == SimilarityType.COSINE) {
                itemSimilarity = new UncenteredCosineSimilarity(dataModel);
            } else if (appSimilarityType == SimilarityType.SPEARMAN) {
                // no SpearmanCorrelationSimilarity for itemSimilarity
                userSimilarity = new SpearmanCorrelationSimilarity(dataModel);
                itemSimilarity = null;
            } else {
                assert false;  // no other possibilities.
            }

            // set userSimilarity to be the same as itemSimilarity.
            // indeed, these two are just different in names.
            if (userSimilarity == null && itemSimilarity != null) {
                userSimilarity = (UserSimilarity) itemSimilarity;
            }
        }

        protected void initRecommender() throws TasteException {
            if (appPreferenceType == PreferenceType.BOOLEAN) {
                recommender = new GenericBooleanPrefItemBasedRecommender(dataModel, itemSimilarity);
            } else if (appPreferenceType == PreferenceType.SCORE) {
                recommender = new GenericItemBasedRecommender(dataModel, itemSimilarity);
            } else {
                assert false;
            }
        }

        // Fixme: if we throw unhandled exception here, who takes care of rollback?
        // Fixme: dirty read? [#1185100]
        protected int genericComputeSave(EntityHandler handler, LongPrimitiveIterator entityIterator, int numEntity, String tableIdentifier, boolean rebuild) throws TasteException, SQLException, IOException {
            BufferedWriter writer = null;
            BatchUploader deleteBatch = null;
            BatchUploader insertBatch = null;
            Connection connection = null;


            // test whether to use datafile or database, which are very different approach.
            if (appDataUseFile) {
                logger.info("Using data file to save recommendation results.");
                String outputFilename = null;
                if (tableIdentifier.equals("similarity")) {
                    outputFilename = appSimilarityFilename;
                } else if (tableIdentifier.equals("prediction")) {
                    outputFilename = appPredictionFilename;
                } else {
                    assert false : "Parameter error.";
                }
                writer = new BufferedWriter(new FileWriter(outputFilename));


            } else {
                // handle database, check database status.
                String tableName = "{recommender_" + tableIdentifier + "}";
                Long ts = DrupletUtils.getLong(drupalConnection.queryValue("SELECT max(updated) FROM " + tableName));
                long pastMaxTimestamp = ts == null ? 0 : ts;

                if (pastMaxTimestamp > updatedTimestamp) {
                    logger.severe("Please reinstall Recommender API or manually remove all data from " + tableName + ". It has records with timestamp newer than the current timestamp");
                    throw new DrupletException("Internal database error. Please reinstall recommender module of your Drupal site.");
                }

                // set db connection
                connection = drupalConnection.getConnection();
                connection.setAutoCommit(false);

                // #1428232: some database doesn't support this transaction level.
                if (connection.getMetaData().supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED)) {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                }

                // handle delete
                String deleteSql;
                if (rebuild) {
                    logger.fine("Rebuilding " + tableName + " for recommender app with ID: " + appID);
                    deleteSql = drupalConnection.d("DELETE FROM " + tableName + " WHERE app_id = ? AND updated < ?");
                } else {
                    deleteSql = drupalConnection.d("DELETE FROM " + tableName + " WHERE app_id = ? AND source_eid = ? AND updated < ?");
                }
                deleteBatch = new BatchUploader(null, "Delete-Batch", connection, deleteSql, drupalConnection.getMaxBatchSize());
                if (rebuild) {
                    deleteBatch.put(appID, updatedTimestamp);
                }
                deleteBatch.start();   // start the thread

                // handle insert
                String insertSql = drupalConnection.d("INSERT INTO " + tableName + "(app_id, source_eid, target_eid, score, updated) VALUES(?, ?, ?, ?, ?)");
                insertBatch = new BatchUploader(null, "Insert-Batch", connection, insertSql, drupalConnection.getMaxBatchSize());
                insertBatch.start();
            }


            int count = 0;
            // attention: this might be set by multi-threads, but we shouldn't have atomic problem.
            int resultCount = 0;

            // process data
            if (getThreadsNumber() <= 0) {
                // this is sequential execution.
                // TODO: could perhas remove the sequential mode and use multi-thread mode alone.
                logger.info("Start computing recommendation and saving results in serial.");

                while (entityIterator.hasNext()) {
                    // TODO: implement/use Counter
                    count ++;
                    if (count % PROGRESS_INCREMENT == 0) {
                        logger.info("Processing " + (int)((float)count/(float)numEntity*100) + "% ...");
                    }

                    long entityID1 = entityIterator.nextLong();
                    if (!handler.validateSource(entityID1)) {
                        continue; // skip if prediction_source_filter is not met.
                    }

                    if (!rebuild && !appDataUseFile) {
                        // attention: only do this with database. File doesn't support rebuild for now.
                        deleteBatch.put(appID, entityID1, updatedTimestamp);
                    }
                    for (RecommendedEntity entity : handler.getRecommendedEntity(entityID1)) {
                        long entityID2 = entity.getEntityID();
                        double score = entity.getValue();
                        if (!handler.validateTarget(entityID2) || !handler.validateScore(score)) {
                            continue;
                        }
                        if (appDataUseFile) {
                            writer.write("" + entityID1 + ',' + entityID2 + ',' + score + ',' + updatedTimestamp + "\n");
                        } else {
                            insertBatch.put(appID, entityID1, entityID2, score, updatedTimestamp);
                        }
                        resultCount ++;
                    }
                }
                if (!appDataUseFile) {
                    deleteBatch.accomplish();
                    insertBatch.accomplish();
                }

            } else {
                // this is to compute with multi-threading.
                logger.info("Start computing recommendation and saving results in parallel using threads: " + getThreadsNumber());

                //ThreadPoolExecutor executorService = new ThreadPoolExecutor(getThreadsNumber(), getThreadsNumber(), 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
                ExecutorService executorService = Executors.newFixedThreadPool(getThreadsNumber());
                List<Future<Long>> futuresList = new ArrayList<Future<Long>>();
                
                while (entityIterator.hasNext()) {
                    long entityID1 = entityIterator.nextLong();
                    if (!handler.validateSource(entityID1)) {
                        continue;
                    }

                    Callable<Long> callable = null;
                    if (appDataUseFile) {
                        callable = new RecommenderUseFileCallable(entityID1, writer, handler);
                    } else {
                        callable = new RecommenderCallable(entityID1, rebuild, deleteBatch, insertBatch, handler);
                    }
                    Future<Long> future = executorService.submit(callable);
                    futuresList.add(future);
                }

                logger.info("Successfully added " + futuresList.size() + " entities. Waiting for processing.");
                executorService.shutdown();
                count = 0;
                for (Future<Long> future : futuresList) {
                    count ++;
                    if (count % PROGRESS_INCREMENT == 0) {
                        logger.info("Processed " + (int)((float)count/(float)futuresList.size()*100) + "% ...");
                    }
                    try {
                        resultCount += future.get();
                    } catch (InterruptedException e) {
                        throw new DrupletException(e);
                    } catch (ExecutionException e) {
                        throw new DrupletException(e);
                    }
                }

                try {
                    executorService.awaitTermination(Integer.MAX_VALUE, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    throw new DrupletException(e);
                }

                assert executorService.isTerminated();
                if (!appDataUseFile) {
                    deleteBatch.accomplish();
                    insertBatch.accomplish();
                }
            }


            logger.info("Computing done. Records#: " + resultCount);
            if (appDataUseFile) {
                writer.flush();
                writer.close();
            } else {
                try {
                logger.finest("Waiting for delete batch to be done.");
                    deleteBatch.join();
                    logger.finest("Waiting for insert batch to be done.");
                    insertBatch.join();
                } catch (InterruptedException e) {
                    throw new DrupletException(e);
                }
                connection.commit();
                connection.close();
                logger.info("Done saving data.");
            }

            return resultCount;
        }


        protected void computeSaveSimilarity() throws TasteException, SQLException, IOException {
            numSimilarity = genericComputeSave(defaultItemSimilarityHandler, dataModel.getItemIDs(), dataModel.getNumItems(), "similarity", true);
        }


        protected void computeSavePrediction() throws TasteException, SQLException, IOException {
            numPrediction = genericComputeSave(defaultPredictionHandler, dataModel.getUserIDs(), dataModel.getNumUsers(), "prediction", true);
        }


        protected void run() throws SQLException, TasteException, IOException {
            processTable(); // if input is a SQL, process table first

            logger.info("Initializing data model, similarity and recommender.");
            initDataModel();
            message.append("Users: ").append(dataModel.getNumUsers()).append(". Items: ").append(dataModel.getNumItems()).append(".");
            numUsers = dataModel.getNumUsers();
            numItems = dataModel.getNumItems();

            initSimilarity();
            String className = itemSimilarity != null ? itemSimilarity.getClass().getName() : userSimilarity != null ? userSimilarity.getClass().getName() : "None";
            logger.info("Using similarity class: " + className);

            initRecommender();
            logger.info("Using recommender class: " + recommender.getClass().getName());

            logger.info("Computing and saving similarity data.");
            computeSaveSimilarity();

            logger.info("Computing and saving prediction data.");
            computeSavePrediction();

            int timeSpent = (int) (DrupletUtils.getLocalUnixTimestamp() - updatedTimestamp);
            message.append(" (Time spent: ").append(timeSpent/3600).append("h")
                    .append(timeSpent / 60 % 60).append("m").append(timeSpent%60).append("s)");
        }
    }




    /**
     * Item2Item algorithm. Entirely reuse the base AlgorithmImpl.
     */
    protected class Item2Item extends AlgorithmImpl {}




    protected class Item2ItemIncrement extends Item2Item {
        long lastUpdated;

        @Override
        protected void run() throws SQLException, TasteException, IOException {
            if (appDataUseFile) {
                throw new UnsupportedOperationException("Cannot use file in the incremental mode for item2item.");
            }

            if (appFieldTimestamp == null) {
                throw new ConfigLoadingException("To run incremental algorithm, you need to specify the timestamp field");
            }
            // max(updated) should be the same for both similarity and prediction.
            Object o = drupalConnection.queryValue("SELECT MAX(updated) FROM {recommender_similarity} WHERE app_id=?", appBaseID);
            if (o == null) {
                lastUpdated = 0;
            } else {
                lastUpdated = (Integer)o;  // very strange, sometimes integer, sometimes long.
            }

            // we basically emulate the app w/ base_id so that data saved using the base app_id.
            appID = appBaseID;
            super.run();
        }

        @Override
        protected void computeSaveSimilarity() throws TasteException, SQLException, IOException {
            List<Map<String, Object>> results = drupalConnection.query("SELECT DISTINCT " + appFieldItem + " FROM " + appTable + " WHERE " + appFieldTimestamp + ">?", lastUpdated);
            if (results.size() == 0) {
                logger.info("No updated items.");
                return;
            }
            long[] updatedItemIDs = new long[results.size()];
            int index = 0;
            for (Map<String, Object> record : results) {
                updatedItemIDs[index] = (Long)record.get(appFieldItem);
                index ++;
            }
            numSimilarity = genericComputeSave(defaultItemSimilarityHandler, new LongPrimitiveArrayIterator(updatedItemIDs), updatedItemIDs.length, "similarity", false);
        }

        @Override
        protected void computeSavePrediction() throws TasteException, SQLException, IOException {
            // compute new users
            List<Map<String, Object>> results = drupalConnection.query("SELECT DISTINCT " + appFieldUser + " FROM " + appTable + " WHERE " + appFieldTimestamp + ">?", lastUpdated);
            if (results.size() == 0) {
                logger.info("No updated users.");
                return;
            }
            long[] updatedUserIDs = new long[results.size()];
            int index = 0;
            for (Map<String, Object> record : results) {
                updatedUserIDs[index] = (Long)record.get(appFieldUser);
                index ++;
            }

            // <del>TODO: [#1188294] for multi-dbms support.</del> we'll load data manually.
            // reload similarity data from database rather than compute again.
            List<GenericItemSimilarity.ItemItemSimilarity> precomputedSimilarity = new ArrayList<GenericItemSimilarity.ItemItemSimilarity>();
            List<Map<String, Object>> results1 = drupalConnection.query("SELECT source_eid, target_eid, score FROM {recommender_similarity} WHERE app_id=?", appID);
            for (Map<String, Object> record : results1) {
                precomputedSimilarity.add(new GenericItemSimilarity.ItemItemSimilarity((Long)record.get("source_eid"), (Long)record.get("target_eid"), (Float)record.get("score")));
            }

            // override itemSimilarity to use existing similarity, and reload recommender
            itemSimilarity = new GenericItemSimilarity(precomputedSimilarity);
            initRecommender();
            numPrediction = genericComputeSave(defaultPredictionHandler, new LongPrimitiveArrayIterator(updatedUserIDs), updatedUserIDs.length, "prediction", false);
        }
    }



    protected class User2User extends AlgorithmImpl {
        // TODO: make this configurable.
        private final int NEAREST_N = 20;   // this is only used in recommendation. the number of saved similar users is still controlled by appMaxKeep
        private final double MIN_SIMILARITY = 0.1;

        @Override
        protected void initRecommender() throws TasteException {
            NearestNUserNeighborhood neighbor = new NearestNUserNeighborhood(NEAREST_N, MIN_SIMILARITY, userSimilarity, dataModel);
            if (appPreferenceType == PreferenceType.BOOLEAN) {
                recommender = new GenericBooleanPrefUserBasedRecommender(dataModel, neighbor, userSimilarity);
            } else if (appPreferenceType == PreferenceType.SCORE) {
                recommender = new GenericUserBasedRecommender(dataModel,neighbor, userSimilarity);
            } else {
                assert false;
            }
        }

        @Override
        protected void computeSaveSimilarity() throws TasteException, SQLException, IOException {
            numSimilarity = genericComputeSave(defaultUserSimilarityHandler, dataModel.getUserIDs(), dataModel.getNumUsers(), "similarity", true);
        }

    }



    protected class SVD extends AlgorithmImpl {
        // TODO: make this configurable
        private final int NUM_FEATURES = 10; // 10 features should be big enough. might need to shrink for better noise-reduction.
        private final int NUM_ITERATIONS = 100;

        @Override
        protected void initSimilarity() {
            logger.info("Skip similarity for SVD algorithm.");
        }

        @Override
        protected void initRecommender() throws TasteException {
            // TODO: make this configurable
            Factorizer factorizer = new ExpectationMaximizationSVDFactorizer(dataModel, NUM_FEATURES, NUM_ITERATIONS);
            recommender = new SVDRecommender(dataModel, factorizer);
        }

        @Override
        protected void computeSaveSimilarity() throws TasteException, SQLException {
            logger.info("Skip computing and saving similarity for SVD algorithm.");
        }

    }

}

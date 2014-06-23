package org.drupal.project.recommender.test;

import org.drupal.project.async_command.*;
import org.drupal.project.recommender.RecommenderApp;
import org.junit.Test;

import java.io.*;
import java.sql.Connection;
import java.sql.SQLException;

import static junit.framework.Assert.assertTrue;

public class RecommenderTest {

    @Test
    public void testPingMe() throws SQLException {
        // create a pingme command.
        // attention: this drupal connection is the one Drush is using. could be different from the one in the drupalConnection created earlier
        String output = DrupletUtils.executeDrush("async-command", "recommender", "PingMe", "From UnitTest", "string1=Hello");
        System.out.println("Drush output: " + output);

        DrupalConnection drupalConnection = new DrupalConnection(DrupletConfig.load());
        drupalConnection.connect();
        Long id = DrupletUtils.getLong(drupalConnection.queryValue("SELECT max(id) FROM {async_command}"));
        RecommenderApp druplet = new RecommenderApp(DrupletConfig.load());
        druplet.run();

        // run() closes the drupal connection, so we recreate again.
        drupalConnection.connect(true);
        assertTrue(output.trim().endsWith(id.toString()));
        CommandRecord record = drupalConnection.retrieveCommandRecord(id);
        assertTrue(record.getStatus().equals(AsyncCommand.Status.SUCCESS));
        assertTrue(record.getMessage().endsWith("Hello"));
    }

    @Test
    public void testRecommender() throws Throwable {
        DrupalConnection drupalConnection = new DrupalConnection(DrupletConfig.load());
        drupalConnection.connect();

        // prepare test recommender app
        String params = "{\"algorithm\":\"item2item\",\"table\":\"{recommender_preference_staging}\",\"fields\":[\"source_eid\",\"target_eid\",\"score\",\"updated\"],\"entity_type\":{\"similarity\":[\"node\",\"node\"],\"prediction\":[\"users\",\"node\"]},\"performance\":\"memory\",\"preference\":\"score\"}";
        drupalConnection.update("DELETE FROM {recommender_app} WHERE name='unittest'");
        long appId = drupalConnection.insertAutoIncrement("INSERT INTO {recommender_app}(name, title, params) VALUES (?, ?, ?)", "unittest", "UnitTest Recommender", params);

        drupalConnection.update("TRUNCATE TABLE {recommender_preference_staging}");
        BatchUploader insert = new BatchUploader("InsertGroupLens", drupalConnection.getConnection(), drupalConnection.d("INSERT INTO {recommender_preference_staging}(source_eid, target_eid, score) VALUES (?, ?, ?)"));
        insert.start();
        insert.put(1, 1, 1);
        insert.put(1, 2, 5);
        insert.put(1, 4, 2);
        insert.put(1, 5, 4);
        insert.put(2, 1, 4);
        insert.put(2, 2, 2);
        insert.put(2, 4, 5);
        insert.put(2, 5, 1);
        insert.put(2, 6, 2);
        insert.put(3, 1, 2);
        insert.put(3, 2, 4);
        insert.put(3, 3, 3);
        insert.put(3, 6, 5);
        insert.put(4, 1, 2);
        insert.put(4, 2, 4);
        insert.put(4, 4, 5);
        insert.put(4, 5, 1);
        insert.accomplish();
        insert.join();

        DrupletUtils.executeDrush("recommender", "unittest", "From UnitTest");
        RecommenderApp druplet = new RecommenderApp(DrupletConfig.load());
        druplet.run();

        // run() closes the drupal connection, so we recreate again.
        drupalConnection.connect(true);
        long similarity = DrupletUtils.getLong(drupalConnection.queryValue("SELECT count(*) FROM {recommender_similarity} WHERE app_id=?", appId));
        assertTrue(similarity > 0);
        long prediction = DrupletUtils.getLong(drupalConnection.queryValue("SELECT count(*) FROM {recommender_prediction} WHERE app_id=?", appId));
        assertTrue(prediction > 0);

        assertTrue(4 < (Float) drupalConnection.queryValue("SELECT score FROM {recommender_prediction} WHERE app_id=? AND source_eid=1 AND target_eid=6", appId));
        // item-itme similarity.
        //assertTrue(-0.5 > (Float) drupalConnection.queryValue("SELECT score FROM {recommender_similarity} WHERE app_id=? AND source_eid=4 AND target_eid=5", appId));
        DrupletUtils.executeDrush("eval", "recommender_app_unregister('unittest');");
    }

    @Test
    public void testRecommenderView() throws Throwable {
        DrupalConnection drupalConnection = new DrupalConnection(DrupletConfig.load());
        drupalConnection.connect();

        drupalConnection.update("DELETE FROM {recommender_app} WHERE name='testview'");
//        String recStr =
//                "  array(" +
//                "    'testview' => array(" +
//                "      'title' => 'Test staging=view recommender'," +
//                "      'params' => array(" +
//                "        'algorithm' => 'item2item'," +
//                "        'table' => '{recommender_preference}',\n" +
//                "        'entity_type' => array(" +
//                "          'similarity' => array('node', 'node')," +
//                "          'prediction' => array('users', 'node')" +
//                "        )," +
//                "        'performance' => 'memory'," +
//                "        'preference' => 'score'," +
//                "        'staging' => 'view'," +
//                "      )" +
//                "    )" +
//                "  )";
//        DrupletUtils.executeDrush("eval", "recommender_app_register(" + recStr + ");");
        String params = "{\"algorithm\":\"item2item\",\"table\":\"<BUILTIN>\",\"fields\":[\"source_eid\",\"target_eid\",\"score\",\"updated\"],\"entity_type\":{\"similarity\":[\"node\",\"node\"],\"prediction\":[\"users\",\"node\"]},\"performance\":\"memory\",\"preference\":\"score\",\"staging\":\"view\"}";
        //long appID = DrupletUtils.getLong(drupalConnection.queryValue("SELECT id FROM {recommender_app} WHERE name=?", "testview"));
        long appID = drupalConnection.insertAutoIncrement("INSERT INTO {recommender_app}(name, title, params) VALUES (?, ?, ?)", "testview", "UnitTest Recommender", params);

        Connection con = drupalConnection.getConnection();
        BatchUploader insert = new BatchUploader("InsertGroupLens", con, drupalConnection.d("INSERT INTO {recommender_preference}(app_id, source_eid, target_eid, score) VALUES (?, ?, ?, ?)"));
        insert.start();
        insert.put(appID, 1, 1, 1);
        insert.put(appID, 1, 2, 5);
        insert.put(appID, 1, 4, 2);
        insert.put(appID, 1, 5, 4);
        insert.put(appID, 2, 1, 4);
        insert.put(appID, 2, 2, 2);
        insert.put(appID, 2, 4, 5);
        insert.put(appID, 2, 5, 1);
        insert.put(appID, 2, 6, 2);
        insert.put(appID, 3, 1, 2);
        insert.put(appID, 3, 2, 4);
        insert.put(appID, 3, 3, 3);
        insert.put(appID, 3, 6, 5);
        insert.put(appID, 4, 1, 2);
        insert.put(appID, 4, 2, 4);
        insert.put(appID, 4, 4, 5);
        insert.put(appID, 4, 5, 1);
        insert.accomplish();
        insert.join();
        con.commit();
        con.close();

        DrupletUtils.executeDrush("recommender", "testview", "From UnitTest");
        RecommenderApp druplet = new RecommenderApp(DrupletConfig.load());
        druplet.run();

        // run() closes the drupal connection, so we recreate again.
        drupalConnection.connect(true);
        long similarity = DrupletUtils.getLong(drupalConnection.queryValue("SELECT count(*) FROM {recommender_similarity} WHERE app_id=?", appID));
        assertTrue(similarity > 0);
        long prediction = DrupletUtils.getLong(drupalConnection.queryValue("SELECT count(*) FROM {recommender_prediction} WHERE app_id=?", appID));
        assertTrue(prediction > 0);

        assertTrue(4 < (Float) drupalConnection.queryValue("SELECT score FROM {recommender_prediction} WHERE app_id=? AND source_eid=1 AND target_eid=6", appID));
        // item-itme similarity.
        //assertTrue(-0.5 > (Float) drupalConnection.queryValue("SELECT score FROM {recommender_similarity} WHERE app_id=? AND source_eid=4 AND target_eid=5", appId));
        //DrupletUtils.executeDrush("eval", "recommender_app_unregister('testview');");
        drupalConnection.update("DELETE FROM {recommender_preference} WHERE app_id=?", appID);
        drupalConnection.update("DELETE FROM {recommender_similarity} WHERE app_id=?", appID);
        drupalConnection.update("DELETE FROM {recommender_prediction} WHERE app_id=?", appID);
        drupalConnection.update("DELETE FROM {recommender_app} WHERE name='testview'");
    }


    @Test
    public void testRecommenderUseFile() throws Throwable {
        DrupalConnection drupalConnection = new DrupalConnection(DrupletConfig.load());
        drupalConnection.connect();

        drupalConnection.update("DELETE FROM {recommender_app} WHERE name='testfile'");
        String params = "{\"algorithm\":\"item2item\",\"table\":\"<FILE>\",\"entity_type\":{\"similarity\":[\"node\",\"node\"],\"prediction\":[\"users\",\"node\"]},\"preference\":\"score\"}";
        //long appID = DrupletUtils.getLong(drupalConnection.queryValue("SELECT id FROM {recommender_app} WHERE name=?", "testview"));
        long appID = drupalConnection.insertAutoIncrement("INSERT INTO {recommender_app}(name, title, params) VALUES (?, ?, ?)", "testfile", "UnitTest Recommender: FileRecommender", params);

        File f0 = new File("/tmp/testfile");
        BufferedWriter writer = new BufferedWriter(new FileWriter(f0));
        outputRow(writer, 1, 1, 1);
        outputRow(writer, 1, 2, 5);
        outputRow(writer, 1, 4, 2);
        outputRow(writer, 1, 5, 4);
        outputRow(writer, 2, 1, 4);
        outputRow(writer, 2, 2, 2);
        outputRow(writer, 2, 4, 5);
        outputRow(writer, 2, 5, 1);
        outputRow(writer, 2, 6, 2);
        outputRow(writer, 3, 1, 2);
        outputRow(writer, 3, 2, 4);
        outputRow(writer, 3, 3, 3);
        outputRow(writer, 3, 6, 5);
        outputRow(writer, 4, 1, 2);
        outputRow(writer, 4, 2, 4);
        outputRow(writer, 4, 4, 5);
        outputRow(writer, 4, 5, 1);
        writer.close();

        DrupletUtils.executeDrush("async-command", "recommender", "RunRecommender", "From UnitTest", "id1="+appID+"|string1=/tmp/testfile|control=ONCE");
        RecommenderApp druplet = new RecommenderApp(DrupletConfig.load());
        druplet.setRunningMode(Druplet.RunningMode.ONCE);
        druplet.run();

        File f1 = new File("/tmp/testfile.simi");
        File f2 = new File("/tmp/testfile.pred");
        assertTrue(f1.exists());
        assertTrue(f2.exists());

        //f0.delete();
        //f1.delete();
        //f2.delete();
    }

    private void outputRow(Writer writer, int id1, int id2, float score) throws IOException {
        writer.write("" + id1 + ',' + id2 + ',' + score + "\n");
    }

}

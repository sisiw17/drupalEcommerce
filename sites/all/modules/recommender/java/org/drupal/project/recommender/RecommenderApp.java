package org.drupal.project.recommender;

import org.drupal.project.async_command.*;

import java.util.Properties;

/**
 * druplet that runs recommender.
 */
@Identifier("recommender")
public class RecommenderApp extends Druplet {

    /**
     * Register acceptable AsyncCommand classes in constructor.
     *
     * @param drupalConnection Connection to a Drupal database that has the {async_command} table.
     */
    @Deprecated
    public RecommenderApp(DrupalConnection drupalConnection) {
        super(drupalConnection);
        registerCommandClass(RunRecommender.class);
    }

    @Deprecated
    public RecommenderApp(Properties config) {
        super(config);
        registerCommandClass(RunRecommender.class);
    }

    public RecommenderApp(DrupletConfig config) {
        super(config);
        registerCommandClass(RunRecommender.class);
    }

    public static void main(String[] args) {
        CommandLineLauncher launcher = new CommandLineLauncher(RecommenderApp.class);
        launcher.launch(args);
    }

//    public void registerRecommender() {
//
//    }
}

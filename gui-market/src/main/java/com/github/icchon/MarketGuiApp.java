package com.github.icchon;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MarketGuiApp extends Application {
    private MarketController _controller;

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("market.fxml"));
        Parent root = loader.load();
        _controller = loader.getController();

        primaryStage.setTitle("FIX-ME Market GUI");
        primaryStage.setScene(new Scene(root, 600, 500));
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (_controller != null) {
            _controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

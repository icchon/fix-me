package com.github.icchon;

import com.github.icchon.client.MarketClient;
import com.github.icchon.protocol.Config;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;

public class MarketController {
    @FXML private Label statusLabel;
    @FXML private ListView<String> orderList;
    @FXML private TextArea logArea;

    private MarketClient _marketClient;
    private boolean _logonSent = false;

    @FXML
    public void initialize() {
        setupMarketClient();
    }

    private void setupMarketClient() {
        String host = Config.get("ROUTER_HOST", "localhost");
        int port = Config.getInt("ROUTER_PORT", 5001);
        
        System.out.println("[DEBUG] Market GUI configured for " + host + ":" + port);

        _marketClient = new MarketClient(host, port) {
            @Override
            protected void onConnected() {
                super.onConnected();
                // 接続直後に自動で Logon するのをやめ、ユーザーの操作を待つか、
                // あるいは Logon ボタン押下時に接続 -> Logon の順で行う
                Platform.runLater(() -> logArea.appendText("TCP Connection established. Waiting for Logon...\n"));
            }
        };
        
        _marketClient.setRawMessageListener(msg -> {
            Platform.runLater(() -> {
                logArea.appendText(msg + "\n");
            });
        });

        _marketClient.setOnExecution((message, execId) -> {
            String clOrdId = message.body().get(11);
            String symbol = message.body().get(55);
            String sender = message.body().get(49);
            Platform.runLater(() -> {
                orderList.getItems().add(0, String.format("[%s] Order %s from %s: %s", execId, clOrdId, sender, symbol));
            });
        });

        _marketClient.setOnLogonStatusChanged(() -> {
            Platform.runLater(this::updateStatusUI);
        });

        // UIステータス監視スレッド
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                    Platform.runLater(this::updateStatusUI);
                } catch (InterruptedException e) { break; }
            }
        }, "Market-Status-Thread").start();
    }

    private void updateStatusUI() {
        if (_marketClient.isLoggedOn()) {
            statusLabel.setText("Status: Logged On (ID: " + _marketClient.getId() + ")");
            statusLabel.setStyle("-fx-text-fill: green;");
        } else if (_marketClient.isRunning()) {
            statusLabel.setText("Status: Opening...");
            statusLabel.setStyle("-fx-text-fill: orange;");
        } else {
            statusLabel.setText("Status: Disconnected");
            statusLabel.setStyle("-fx-text-fill: gray;");
        }
    }

    @FXML
    private void handleLogon() {
        new Thread(() -> {
            if (!_marketClient.isRunning()) {
                Platform.runLater(() -> logArea.appendText("Opening Market (starting network)...\n"));
                try {
                    _marketClient.start();
                } catch (Exception e) {
                    Platform.runLater(() -> logArea.appendText("Failed to open market: " + e.getMessage() + "\n"));
                    return;
                }
            } else {
                Platform.runLater(() -> logArea.appendText("Market is already open.\n"));
            }
        }).start();
    }

    @FXML
    private void handleDisconnect() {
        if (_marketClient != null) {
            _marketClient.stop(); // ネットワークスレッドを完全に停止し、ソケットを閉じる
            logArea.appendText("Disconnected and network thread stopped.\n");
        }
    }

    @FXML
    private void handleClearLog() {
        logArea.clear();
    }

    public void shutdown() {
        if (_marketClient != null) {
            _marketClient.stop();
        }
    }
}

package com.github.icchon;

import com.github.icchon.client.BrokerClient;
import com.github.icchon.protocol.Config;
import com.github.icchon.protocol.FixParser;
import com.github.icchon.protocol.MarketInfo;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;

public class BrokerController {
    @FXML private Label statusLabel;
    @FXML private TextField marketIdField;
    @FXML private TextField symbolField;
    @FXML private TextField qtyField;
    @FXML private TextField priceField;
    @FXML private ListView<String> reportList;
    @FXML private TextArea logArea;

    private BrokerClient _brokerClient;
    private String _idIssuerUrl;

    @FXML
    public void initialize() {
        _idIssuerUrl = Config.get("ID_ISSUER_URL", "http://localhost:8081");
        setupBrokerClient();
    }

    private void setupBrokerClient() {
        String host = Config.get("ROUTER_HOST", "localhost");
        int port = Config.getInt("ROUTER_PORT", 0); // 0を指定して自動発見を有効にする

        _brokerClient = new BrokerClient(host, port) {
            @Override
            protected void handleMessage(FixParser.ParsedData message) {
                super.handleMessage(message);
                String msgType = message.body().get(35);
                if ("8".equals(msgType)) {
                    String status = message.body().get(39);
                    String clOrdId = message.body().get(11);
                    String displayStatus = "2".equals(status) ? "FILLED" : "REJECTED";
                    Platform.runLater(() -> {
                        reportList.getItems().add(0, String.format("Report: Order %s is %s", clOrdId, displayStatus));
                    });
                }
            }
        };

        _brokerClient.setRawMessageListener(msg -> {
            Platform.runLater(() -> {
                logArea.appendText(msg + "\n");
            });
        });

        _brokerClient.setOnLogonStatusChanged(() -> {
            Platform.runLater(this::updateStatusUI);
        });

        // UIステータス監視スレッド (念のためポーリングも残す)
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                    Platform.runLater(this::updateStatusUI);
                } catch (InterruptedException e) { break; }
            }
        }, "Broker-Status-Thread").start();
    }

    private void updateStatusUI() {
        if (_brokerClient.isLoggedOn()) {
            statusLabel.setText("Status: Logged On (ID: " + _brokerClient.getId() + ")");
            statusLabel.setStyle("-fx-text-fill: green;");
        } else if (_brokerClient.isRunning()) {
            statusLabel.setText("Status: Logging In...");
            statusLabel.setStyle("-fx-text-fill: orange;");
        } else {
            statusLabel.setText("Status: Disconnected");
            statusLabel.setStyle("-fx-text-fill: gray;");
        }
    }

    private void ensureConnected() {
        if (!_brokerClient.isConnected()) {
            try {
                _brokerClient.start();
            } catch (Exception e) {
                Platform.runLater(() -> logArea.appendText("Connection Error: " + e.getMessage() + "\n"));
            }
            
            // 接続完了を待つ (最大 5秒)
            long start = System.currentTimeMillis();
            while (!_brokerClient.isConnected() && System.currentTimeMillis() - start < 5000) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        }
    }

    @FXML
    private void handleDiscovery() {
        new Thread(() -> {
            List<MarketInfo> markets = _brokerClient.discoverMarkets(_idIssuerUrl);
            Platform.runLater(() -> {
                if (markets.isEmpty()) {
                    logArea.appendText("No markets discovered.\n");
                } else {
                    logArea.appendText("Discovered Markets:\n");
                    for (MarketInfo m : markets) {
                        logArea.appendText(String.format("  - %s (%s)\n", m.marketName(), m.marketId()));
                    }
                }
            });
        }).start();
    }

    @FXML
    private void handleLogon() {
        new Thread(() -> {
            String targetMarket = marketIdField.getText();
            
            // RouterからのID割り当てを待って接続を開始する
            ensureConnected();
            Platform.runLater(() -> _brokerClient.sendLogon(targetMarket));
        }).start();
    }

    @FXML
    private void handleDisconnect() {
        if (_brokerClient != null) {
            new Thread(() -> {
                String target = marketIdField.getText();
                _brokerClient.sendLogout(target);
                
                // しばらく待ってから停止
                try { Thread.sleep(500); } catch (InterruptedException e) {}
                
                _brokerClient.stop();
                Platform.runLater(() -> logArea.appendText("Logout sent and network thread stopped.\n"));
            }).start();
        }
    }

    @FXML
    private void handleKillConnection() {
        if (_brokerClient != null) {
            _brokerClient.forceDisconnect();
            logArea.appendText("[FATAL] Forcing abrupt connection kill...\n");
        }
    }

    @FXML
    private void handlePlaceBuyOrder() {
        placeOrder("1");
    }

    @FXML
    private void handlePlaceSellOrder() {
        placeOrder("2");
    }

    private void placeOrder(String side) {
        new Thread(() -> {
            ensureConnected();
            Platform.runLater(() -> {
                try {
                    _brokerClient.placeOrder(
                            marketIdField.getText(),
                            symbolField.getText(),
                            Integer.parseInt(qtyField.getText()),
                            Double.parseDouble(priceField.getText()),
                            side
                    );
                } catch (Exception ex) {
                    logArea.appendText("Error placing order: " + ex.getMessage() + "\n");
                }
            });
        }).start();
    }

    @FXML
    private void handleClearLog() {
        logArea.clear();
    }

    public void shutdown() {
        if (_brokerClient != null) {
            _brokerClient.stop();
        }
    }
}

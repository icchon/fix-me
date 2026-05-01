# FIX Communication Infrastructure Tasks (Simple Proxy Model)

42 FIXme 要件に基づいた、シンプルで堅牢なメッセージルーターの実装。

## Phase 1: 基礎インフラの構築
- [x] **非ブロッキング I/O (NIO) の実装**
- [x] **ID 割り当てとルーティングテーブルの管理**
- [x] **チェックサムバリデーション (FixParser)**
- [x] **透明なメッセージ転送 (Forwarding)**

## Phase 2: クライアント側の実装 (Broker / Market)
- [ ] **Broker の実装**
    - [ ] Router への接続と ID 取得
    - [ ] Buy/Sell 注文の送信 (FIX 形式)
    - [ ] 実行/拒絶応答の受信
- [ ] **Market の実装**
    - [ ] Router への接続と ID 取得
    - [ ] 注文の受信と実行ロジック (在庫管理)
    - [ ] 実行/拒絶応答の送信 (FIX 形式)

## Phase 3: FIX プロトコルの完全準拠 (クライアント責務)
- [ ] **クライアント側でのシーケンス番号管理**
- [ ] **クライアント側での Heartbeat 送受信**
- [ ] **クライアント側での再送要求 (Resend Request) 処理**

## Phase 4: パフォーマンスと安定性
- [ ] **Java Executor Framework の導入** (メッセージ処理のマルチスレッド化)
- [ ] **エラーハンドリングの強化** (不正なターゲット ID への対処など)

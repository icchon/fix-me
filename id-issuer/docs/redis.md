## Market Registry
システムに存在するMarketのマスター情報。

- **Key形式**: `market:{market_name}`
- **データ構造**: Hash
- **TTL**: 無し（永続）

| Field         | Type | Description         | Example                |
|:--------------| :--- |:--------------------|:-----------------------|
| `market_id`   | String | ID                  | `jklfsasadllgaf`       |
| `market_name` | String | マーケット名              | `market-A`             |
| `domain`      | String | Marketの接続先FQDNまたはIP | `market-a.example.com` |
| `port`        | Integer | 待ち受けポート番号           | `5001`                 |
| `updated_at`  | String | 最終更新日時 (ISO8601)    | `2026-04-23T18:00:00Z` |

## 2. Trading Session
BrokerスレッドとMarketの動的な紐付け。
- **Key形式**: `session:{broker_id}`
- **データ構造**: String (JSON)
- **TTL**: 1,800秒
```json
{
  "broker_session_id": 100001,
  "market": {
    "market_id": "",
    "market_name": "",
    "domain": "",
    "port": "",
  },
  "issued_at": 1713858600,
  "expires_at": "",
}

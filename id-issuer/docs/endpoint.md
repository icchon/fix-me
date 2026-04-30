
GET /markets 
```
RESPONSE
[
    {
    "market_id": "",
    "market_name": "",
    "domain": "",
    "port": "",
    },
]
```

GET /markets/{market_id}
```
RESPONSE
{
    "market_id": "",
    "market_name": "",
    "domain": "",
    "port": "",
}
```

POST /markets
```
REQUEST
{
"market_name": "",
"domain": "",
"port": "",
}
```
```
RESPONSE
{
    "market_id": "",
    "market_name": "",
    "domain": "",
    "port": "",
}
```

PUT /markets/{market_id}
```
REQUEST
{
    "market_name": "",
    "domain": "",
    "port": "",
}
```
```
RESPONSE
{
    "market_id": "",
    "market_name": "",
    "domain": "",
    "port": "",
}
```

DELETE /markets/{market_id}

POST /sessions
```
REQUEST
{
    "market_id": "",
}
```
```
RESPONSE
{   
    "broker_session_id": "",
    "market": {
        "market_id": "",
        "market_name": "",
        "domain": "",
        "port": "",
    },
    "expires_at": "",
}
```

GET /sessions/{broker_session_id}
```
RESPONSE
{
    "broker_session_id": "",
    "market": {
        "market_id": "",
        "market_name": "",
        "domain": "",
        "port": "",
    },
    "expiress_at": "",
}
```

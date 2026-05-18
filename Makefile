all: build up-deps run-id-issuer run-router run-market run-broker

build:
	mvn clean install -DskipTests

test:
	mvn test

run-id-issuer:
	java -jar id-issuer/target/id-issuer-1.0-SNAPSHOT.jar

# ポート 0 を指定すると空きポートを自動で使用し、id-issuer に登録される
run-router:
	BROKER_PORT=$(or $(BROKER_PORT),0) MARKET_PORTS=$(or $(MARKET_PORTS),0) \
	java -DBROKER_PORT=$(or $(BROKER_PORT),0) -DMARKET_PORTS=$(or $(MARKET_PORTS),0) \
	-jar router/target/router-1.0-SNAPSHOT.jar

run-market:
	export ROUTER_PORT=0; export MARKET_NAME=market-A; \
	java -DROUTER_PORT=0 -DMARKET_NAME=market-A -jar market/target/market-1.0-SNAPSHOT.jar

run-broker:
	export ROUTER_PORT=0; \
	java -DROUTER_PORT=0 -jar broker/target/broker-1.0-SNAPSHOT.jar

up-deps:
	docker-compose up -d

down-deps:
	docker-compose down

.PHONY: build test run-id-issuer run-router run-market run-broker up-deps down-deps

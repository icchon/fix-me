all: build up-deps run-id-issuer run-router run-market run-broker run-gui-market run-gui-broker

build:
	mvn clean install -DskipTests

test:
	mvn test

run-id-issuer:
	mvn spring-boot:run -pl id-issuer

# ポート 0 を指定すると空きポートを自動で使用し、id-issuer に登録される
run-router:
	MAVEN_OPTS="-DBROKER_PORT=$(or $(BROKER_PORT),0) -DMARKET_PORTS=$(or $(MARKET_PORTS),0)" \
	mvn exec:java -pl router -Dexec.mainClass="com.github.icchon.Main"

# 2つ目のルーターも全く同じコマンドで（空きポートを使って）起動できる
run-router-2:
	$(MAKE) run-router

run-market:
	mvn exec:java -pl market -Dexec.mainClass="com.github.icchon.Main"

run-broker:
	mvn exec:java -pl broker -Dexec.mainClass="com.github.icchon.Main"

# クライアント側も ROUTER_PORT=0 をデフォルトにし、自動発見させる
run-gui-market:
	export ROUTER_PORT=0; export MARKET_NAME=market-A; \
	MAVEN_OPTS="-DROUTER_PORT=0 -DMARKET_NAME=market-A" mvn javafx:run -pl gui-market

run-gui-broker:
	export ROUTER_PORT=0; \
	MAVEN_OPTS="-DROUTER_PORT=0" mvn javafx:run -pl gui-broker

up-deps:
	docker-compose up -d

down-deps:
	docker-compose down

.PHONY: build test run-id-issuer run-router run-market run-broker run-gui-market run-gui-broker up-deps down-deps

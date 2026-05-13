CP_CORE := fix-core/target/fix-core-1.0-SNAPSHOT.jar
CP_ROUTER := router/target/router-1.0-SNAPSHOT.jar:$(CP_CORE)
CP_MARKET := market/target/market-1.0-SNAPSHOT.jar:$(CP_CORE)
CP_BROKER := broker/target/broker-1.0-SNAPSHOT.jar:$(CP_CORE)

all: build up-redis run-id-issuer run-router run-market run-broker

build:
	mvn clean install

run-id-issuer:
	mvn spring-boot:run -pl id-issuer

run-router:
	java -cp $(CP_ROUTER) com.github.icchon.Main

run-market:
	java -cp $(CP_MARKET) com.github.icchon.Main

run-broker:
	java -cp $(CP_BROKER) com.github.icchon.Main

up-redis:
	docker-compose up -d redis

down-redis:
	docker-compose down

.PHONY: build run-id-issuer run-router run-market run-broker up-redis down-redis

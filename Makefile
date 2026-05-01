CP_CORE := fix-core/target/fix-core-1.0-SNAPSHOT.jar
CP_ROUTER := router/target/router-1.0-SNAPSHOT.jar:$(CP_CORE)
CP_MARKET := market/target/market-1.0-SNAPSHOT.jar:$(CP_CORE)
CP_BROKER := broker/target/broker-1.0-SNAPSHOT.jar:$(CP_CORE)

build:
	mvn clean install

run-router:
	java -cp $(CP_ROUTER) com.github.icchon.Main

run-market:
	java -cp $(CP_MARKET) com.github.icchon.Main

run-broker:
	java -cp $(CP_BROKER) com.github.icchon.Main


.PHONY: build run-router run-market run-broker

help:
	echo "commands: build, run; \nbuild; \nrun [APIADDR="127.0.0.1:9085"] [DATADIR] [LOGDIR] [NETADDR="127.0.0.1:9088"] [SECONDPEER="127.0.0.1:9089"] [RESTAPIADDR] [WALLETDIR]\n"
build:
	sbt assembly
run: build
	APIADDR=$(or $(APIADDR), "127.0.0.1:9086") \
	RESTAPIADDR=$(or $(RESTAPIADDR), "127.0.0.1:6555") \
	DATADIR=$(or $(DATADIR), ".blockchain/data") \
	LOGDIR=$(or $(LOGDIR), ".blockchain/log") \
	NETADDR=$(or $(NETADDR), "127.0.0.1:9089") \
	SECONDPEER=$(or $(SECONDPEER), "127.0.0.1:9088") \
	WALLETDIR=$(or $(WALLETDIR), ".blockchain/wallet") \
	java -jar target/scala*/Aeneas-assembly-*.jar -Dlogback.configurationFile=src/main/resources/logback.xml

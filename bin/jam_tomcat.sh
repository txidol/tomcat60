
BASE=..
jamvm -Xms32M -cp $BASE/runtime/tomcat-all-runtime.jar:$BASE/repository/mx4j-3.0.1/lib/mx4j.jar org.apache.catalina.startup.Bootstrap start

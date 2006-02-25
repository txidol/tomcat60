#!/bin/sh 

BASE=`pwd`/..
jamvm -Xms32M -cp $BASE/sandbox/runtime/tomcat-runtime.jar:$BASE/repository/mx4j-3.0.1/lib/mx4j.jar org.apache.tomcat.standalone.Main $*

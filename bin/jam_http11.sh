#!/bin/sh

# run from sandbox dir

BASE=`pwd`/..
jamvm -cp $BASE/sandbox/runtime/tomcat-http11.jar:$BASE/repository/rhino1_6R2/js.jar org.apache.coyote.adapters.JsAdapter


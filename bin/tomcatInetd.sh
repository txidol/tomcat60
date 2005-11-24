#!/bin/sh

. $HOME/.bashrc

$BASE=`pwd`/../..
export CLASSPATH=$BASE/sandbox/classes:$BASE/connectors/bin
cd $BASE/sandbox
exec sudo -u costin java org.apache.coyote.standalone.MainInetd >/tmp/tc.log 2>&1 

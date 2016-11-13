#!/bin/sh
# -------------------------------------------------------------------------
# crawler-ipservice Bootstrap Script for Linux
# -------------------------------------------------------------------------
#


# echo function library
filepath=$(cd "$(dirname "$0")"; pwd)
JAVA_OPTS="-server -Xss256k -Xms3g -Xmx3g -Xmn768m -XX:PermSize=256m -XX:MaxPermSize=256m -XX:-UseGCOverheadLimit -XX:+UseConcMarkSweepGC -XX:AutoBoxCacheMax=20000"
RMI_OPTS="-Djava.rmi.server.disableHttp=true"

PROC_NAME="crawler-ipservice"
PROC_NUM=`ps -ef |grep "procname=$PROC_NAME" |grep -v grep |wc -l`
PID=`ps -ef |grep "procname=$PROC_NAME" |grep -v grep |awk '{print $2}'`

case "$1" in
'start')
        echo "START $PROC_NAME \c\r\n"

        if [ $PROC_NUM -eq 0 ]; then
           nohup java -Dprocname=$PROC_NAME  -jar $filepath/lib/crawler-ipservice-1.0-SNAPSHOT.jar JAVA_OPTS > ~/null 2>&1 &

           sleep 1

           PROC_NUM=`ps -ef |grep "procname=$PROC_NAME" |grep -v grep |wc -l`
           if [ $PROC_NUM -gt 0 ]; then
              echo "SUCCESS:crawler-ipservice having started!"
           else
              echo "ERROR:crawler-ipservice start failed!"
           fi
        else
           echo "WARNING:ALREADY RUNNING!"
        fi
        ;;
'stop')
        echo "STOP $PROC_NAME \c"

        if [ $PROC_NUM -gt 0 ]; then
           kill $PID
           sleep 2

           PROC_NUM=`ps -ef |grep "procname=$PROC_NAME" |grep -v grep |wc -l`
           if [ $PROC_NUM -eq 0 ]; then
              echo "SUCCESS:crawler-ipservice STOP"
           else
              echo "ERROR:crawler-ipservice stop failed!"
           fi
        else
           echo "WARNING:crawler-ipservice NOT RUNNING!"
        fi
        ;;

'status')
        echo "  $PROC_NAME  ----- "$PID"\c"

        if [ $PROC_NUM -gt 0 ]; then
           echo "RUNNING"
        else
           echo "STOPPED"
        fi
        ;;

*)
        echo
        echo_xxx succ " Usage:"
        echo_xxx succ "         $0 { start | stop | status }"
        echo
        exit 1
        ;;
esac
exit 0

#!/bin/bash

export op="bootstrap-start"

if [[ -f "../setenv.sh" ]];then
. ../setenv.sh
fi

getProcessNo(){
 echo "$(ps -ef|grep zephyr|grep -v grep|awk '{print $2}')"
}

ProcessNo=$(getProcessNo)
if [[ -n "${ProcessNo}" ]]; then
  echo "Find Process Info ..."
  echo "ProcessNo: ${ProcessNo}"
  sleep 1
  kill "${ProcessNo}"
  echo "Waiting Process Exit ..."
  c=1
  while true;
  do
    if [[ -z $(getProcessNo) ]];then
      break;
    fi
    sleep 1
    echo "[${c}s]Check Process If Exit..."
    c=$((c+1))
  done
  echo "${ProcessNo} was killed."
fi

JAVA_OPTIONS="-DAPP_HOME=`pwd`"
while read option
do
   JAVA_OPTIONS=${option}" "${JAVA_OPTIONS}
done < jvm.options

nohup java "${JAVA_OPTIONS}" -jar ../../lib/*.jar &
echo "zephyr started.\n"

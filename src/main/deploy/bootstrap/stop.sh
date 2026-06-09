#!/bin/bash

export op="bootstrap-stop"

if [[ -f "../../setenv.sh" ]];then
. ../../setenv.sh
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

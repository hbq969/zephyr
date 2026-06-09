#!/bin/bash

export op="bootstrap-check"

if [[ -f "../setenv.sh" ]];then
. ../setenv.sh
fi


# shellcheck disable=SC2009
P=$(ps -ef| grep "zephyr"| grep -v grep| awk '{print $2}')

if [[ -n "${P}" ]]; then
  echo "Find Process Info ..."
  echo "ProcessNo: ${ProcessNo}"
fi

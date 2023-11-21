#!/bin/bash

while [[ $# -gt 0 ]]; do
  case $1 in
    --host)
      HOSTS=("$2")
      shift # past argument
      shift # past value
      ;;
    --file)
      FILE="$2"
      shift
      shift
      ;;
    --help)
      echo "Options:"
      printf "--host Optional. Specifies the remote host to run getJar on\n"
      printf "--file Optional. Specifies a file containing a list on hosts, one on each line to run getJar on\n"
      exit
      ;;
    -*|--*)
      echo "Unknown option $1"
      exit 1
      ;;
  esac
done

if [ -z ${HOSTS+x} ] && [ -z ${FILE+x} ]; then
  echo "--host or --file is required"
  exit
fi

if [[ -n ${FILE+x} ]]; then
  readarray -t HOSTS < "$FILE"
fi

for HOST in "${HOSTS[@]}"
do
  echo "HOST: $HOST"
   ssh "davidk@$HOST" -t "export JAVA_HOME=/usr/lib/jvm/default-java; ~/Desktop/Distributed/jraft/experiments/getJar.sh"
done





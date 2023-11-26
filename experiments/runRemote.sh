#!/bin/bash

while [[ $# -gt 0 ]]; do
  case $1 in
    --file)
      FILE="$2"
      shift
      shift
      ;;
    --help)
      echo "Options:"
      printf "--file Required. Specifies a file containing a list on hosts, one on each line to run addServer on\n"
      exit
      ;;
    -*|--*)
      echo "Unknown option $1"
      exit 1
      ;;
  esac
done

if [[ -n ${FILE+x} ]]; then
  readarray -t HOSTS < "$FILE"
  NUM_HOSTS="${#HOSTS[@]}"
else
  echo "--file is required"
  exit
fi

# Loop through the array
for INDEX in "${!HOSTS[@]}"
do
   ID=$((INDEX + 1))
   HOST="${HOSTS[$INDEX]}"
   echo "Creating server $ID on $HOST"
   ssh -f "ec2-user@${HOST}" "sh -c 'cd /home/ec2-user/Projects/baseImplementation/experiments/; nohup ./addServer.sh --id ${ID} --size $NUM_HOSTS --seedIp ${HOSTS[0]} > stdout-log.log 2>&1 &'"
   sleep 1
done
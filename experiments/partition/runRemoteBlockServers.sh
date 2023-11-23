#!/bin/bash

while [[ $# -gt 0 ]]; do
  case $1 in
    --hostsFile)
      HOSTS_FILE="$2"
      shift
      shift
      ;;
    --partitionFile)
      PARTITION_FILE="$2"
      shift
      shift
      ;;
    --help)
      echo "Options:"
      printf "--hostsFile Required. Specifies a file containing a list on hosts, one on each line to run deleteAllBlocked.sh on\n"
      printf "--partitionFile Required. Specifies the network partition to be created\n"
      exit
      ;;
    -*|--*)
      echo "Unknown option $1"
      exit 1
      ;;
  esac
done

if [[ -n ${HOSTS_FILE+x} ]]; then
  readarray -t HOSTS < "$HOSTS_FILE"
else
  echo "--hostsFile is required"
  exit
fi

if [ -z ${PARTITION_FILE+x} ]; then
  echo "--partitionFile is required"
  exit
fi

# Loop through the array
for INDEX in "${!HOSTS[@]}"
do
   HOST="${HOSTS[$INDEX]}"
   scp "$PARTITION_FILE" "ec2-user@$HOST:/home/ec2-user/Projects/jraft/experiments/partition/partition.txt"
   ssh "ec2-user@$HOST" -t "/home/ec2-user/Projects/jraft/experiments/partition/blockServers.sh --file partition.txt"
done
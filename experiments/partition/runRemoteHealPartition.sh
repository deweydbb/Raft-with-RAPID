#!/bin/bash

while [[ $# -gt 0 ]]; do
  case $1 in
    --hostsFile)
      HOSTS_FILE="$2"
      shift
      shift
      ;;
    --help)
      echo "Options:"
      printf "--hostsFile Required. Specifies a file containing a list on hosts, one on each line to run deleteAllBlocked.sh on\n"
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

# Loop through the array
for INDEX in "${!HOSTS[@]}"
do
   HOST="${HOSTS[$INDEX]}"
   ssh "ec2-user@$HOST" -t "/home/ec2-user/Projects/jraft/experiments/partition/deleteAllBlocked.sh"
done
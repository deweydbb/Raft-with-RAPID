#!/bin/bash

rm kvstore.jar

cd ..
mvn package
cp ./kvstore/target/kvstore-1.0.0-jar-with-dependencies.jar ./experiments/kvstore.jar
cd experiments

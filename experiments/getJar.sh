#!/bin/bash

cd ..
mvn package
cp ./kvstore/target/kvstore-1.0.0-jar-with-dependencies.jar kvstore.jar
cd experiments
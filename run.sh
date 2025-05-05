#!/bin/bash

if [ "$#" -ne 2 ]; then
    echo "Uso: $0 <endereço> <período>"
    exit 1
fi

ADDRESS=$1
PERIOD=$2

echo "Compilando o projeto..."
./gradlew build

if [ $? -ne 0 ]; then
    echo "Erro na compilação do projeto!"
    exit 1
fi

echo "Executando o roteador com o endereço $ADDRESS e o período $PERIOD..."
./build/bin/native/releaseExecutable/KotlinNativeTemplate.kexe "$ADDRESS" "$PERIOD"

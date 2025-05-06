#!/bin/bash

echo "--- Parando Simulação UDPRIP ---"

# 1. Matar processos de roteador
if command -v tmux &> /dev/null; then
    echo "Tentando matar sessão tmux 'udrip_session'..."
    tmux kill-session -t udrip_session || echo "Sessão tmux 'udrip_session' não encontrada ou já encerrada."
else
    echo "Tentando matar roteadores em segundo plano via PIDs..."
    for pid_file in router_*.pid; do
        if [ -f "$pid_file" ]; then
            PID=$(cat "$pid_file")
            if kill -0 "$PID" 2>/dev/null; then
                echo "Matando processo $PID..."
                kill "$PID"
            else
                echo "Processo $PID não encontrado, removendo arquivo PID."
            fi
            rm "$pid_file"
        fi
    done
fi

# 2. Limpar IPs de Loopback
echo "Removendo IPs de loopback..."
sudo sh lo-addresses.sh del

echo "--- Simulação Parada ---"

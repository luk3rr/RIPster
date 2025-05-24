#!/bin/bash

EXECUTABLE_DIR="./build/bin/native/releaseExecutable"
EXECUTABLE_NAME="router.kexe"

PERIOD=5

ROUTER_IPS=("127.0.1.1" "127.0.1.2" "127.0.1.3" "127.0.1.4" "127.0.1.5")

# Função para iniciar um roteador em uma nova janela/painel do tmux
start_router_tmux() {
    local ip=$1
    local period=$2
    local pane_name="router_${ip//./_}"
    local log_file="router_$ip.log"

    echo "Iniciando roteador $ip no painel '$pane_name'..."
    tmux new-window -t udrip_session -n "$pane_name" -d
    tmux send-keys -t udrip_session:$pane_name "cd $EXECUTABLE_DIR" C-m
    tmux send-keys -t udrip_session:$pane_name "./$EXECUTABLE_NAME $ip $period startup" > "$log_file" 2>&1 C-m
    echo "  Roteador $ip iniciado."
}

# Função para iniciar um roteador em segundo plano (sem janela interativa)
start_router_background() {
    local ip=$1
    local period=$2
    local log_file="router_$ip.log"

    echo "Iniciando roteador $ip em segundo plano (logs em $log_file)..."
    # A saída padrão (stdout) vai para o log, e a saída de erro (stderr) também
    "$EXECUTABLE_DIR/$EXECUTABLE_NAME" "$ip" "$period" "startup" > "$log_file" 2>&1 &
    PID=$!
    echo "  Roteador $ip iniciado com PID: $PID"
    echo "$PID" > "router_$ip.pid" # Salva o PID para poder matar depois
}

# --- Main Script ---

echo "--- Iniciando Simulação UDPRIP ---"

# 1. Limpar e Adicionar IPs de Loopback
echo "Configurando IPs de loopback..."
sudo sh lo-addresses.sh del
sudo sh lo-addresses.sh add
sleep 2 # Dá um tempo para os IPs serem configurados

# 2. Compilar o Projeto (se necessário)
echo "Compilando o projeto (se necessário)..."
./gradlew build || { echo "Falha na compilação. Abortando."; exit 1; }
if [ ! -f "$EXECUTABLE_DIR/$EXECUTABLE_NAME" ]; then
    echo "Erro: Executável '$EXECUTABLE_NAME' não encontrado em '$EXECUTABLE_DIR'. Verifique o caminho e o nome do módulo."
    exit 1
fi
echo "Compilação concluída."

# Pergunta para o usuário qual modo deseja
echo "Escolha o modo de execução dos roteadores:"
echo "1) tmux (painéis separados)"
echo "2) background (processos em segundo plano)"
read -rp "Digite 1 ou 2: " modo

if [[ "$modo" == "1" ]]; then
    if command -v tmux &> /dev/null; then
        echo "Usando tmux para iniciar roteadores em painéis separados."
        tmux new-session -s udrip_session -d
        tmux send-keys -t udrip_session "clear" C-m
        tmux send-keys -t udrip_session "echo 'Sessão UDPRIP. Use Ctrl+b n para próximo, Ctrl+b p para anterior.'" C-m

        for ip in "${ROUTER_IPS[@]}"; do
            start_router_tmux "$ip" "$PERIOD"
        done

        echo "Todos os roteadores iniciados em sessão tmux 'udrip_session'."
        echo "Para anexar: tmux attach-session -t udrip_session"
        echo "Para matar todos: tmux kill-session -t udrip_session"
    else
        echo "tmux não encontrado. Abortando."
        exit 1
    fi
elif [[ "$modo" == "2" ]]; then
    echo "Iniciando roteadores em segundo plano."
    for ip in "${ROUTER_IPS[@]}"; do
        start_router_background "$ip" "$PERIOD"
    done
    echo "Para parar os roteadores: kill \$(cat router_*.pid)"
else
    echo "Opção inválida."
    exit 1
fi

echo "--- Simulação Iniciada ---"

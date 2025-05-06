#!/bin/bash

EXECUTABLE_DIR="./build/bin/native/releaseExecutable"
EXECUTABLE_NAME="router.kexe"

PERIOD=5

ROUTER_IPS=("127.0.1.1" "127.0.1.2" "127.0.1.3" "127.0.1.4" "127.0.1.5")

# Função para iniciar um roteador em uma nova janela/painel do tmux
start_router_tmux() {
    local ip=$1
    local period=$2
    local startup_file=$3
    local pane_name="router_$ip"

    echo "Iniciando roteador $ip no painel '$pane_name'..."
    tmux new-window -n "$pane_name" -d # Cria uma nova janela e a separa
    tmux send-keys -t "$pane_name" "cd $EXECUTABLE_DIR" C-m # Navega para o diretório
    tmux send-keys -t "$pane_name" "./$EXECUTABLE_NAME $ip $period startup_files/router_${ip//./_}.txt" C-m # Executa o roteador
    echo "  Roteador $ip iniciado."
}

# Função para iniciar um roteador em segundo plano (sem janela interativa)
start_router_background() {
    local ip=$1
    local period=$2
    local startup_file=$3
    local log_file="router_$ip.log"

    echo "Iniciando roteador $ip em segundo plano (logs em $log_file)..."
    # A saída padrão (stdout) vai para o log, e a saída de erro (stderr) também
    "$EXECUTABLE_DIR/$EXECUTABLE_NAME" "$ip" "$period" "$startup_file" > "$log_file" 2>&1 &
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

# 3. Iniciar Roteadores
echo "Iniciando roteadores..."

if command -v tmux &> /dev/null; then
    echo "Usando tmux para iniciar roteadores em painéis separados."
    tmux new-session -s udrip_session -d # Cria uma nova sessão tmux em segundo plano
    tmux send-keys -t udrip_session "clear" C-m # Limpa o painel inicial
    tmux send-keys -t udrip_session "echo 'Sessão UDPRIP. Use Ctrl+b n para ir para o próximo roteador, Ctrl+b p para o anterior.'" C-m

    first_ip=${ROUTER_IPS[0]}
    # Inicia o primeiro roteador na janela inicial
    tmux send-keys -t udrip_session "cd $EXECUTABLE_DIR" C-m
    tmux send-keys -t udrip_session "./$EXECUTABLE_NAME $first_ip $PERIOD startup_files/router_${first_ip//./_}.txt" C-m

    # Inicia os roteadores restantes em novas janelas
    for ip in "${ROUTER_IPS[@]:1}"; do # Pega todos os IPs menos o primeiro
        start_router_tmux "$ip" "$PERIOD" "startup_files/router_${ip//./_}.txt"
    done
    echo "Todos os roteadores iniciados em sessão tmux 'udrip_session'."
    echo "Para anexar à sessão, use: tmux attach-session -t udrip_session"
    echo "Para matar todos os roteadores depois: tmux kill-session -t udrip_session"
else
    echo "tmux não encontrado. Iniciando roteadores em segundo plano (menos interativo)."
    for ip in "${ROUTER_IPS[@]}"; do
        start_router_background "$ip" "$PERIOD" "startup_files/router_${ip//./_}.txt"
    done
    echo "Para parar os roteadores, use 'kill $(cat router_*.pid)'"
fi

echo "--- Simulação Iniciada ---"

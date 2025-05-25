#!/bin/bash

EXECUTABLE_DIR="./build/bin/native/releaseExecutable"
EXECUTABLE_NAME="router.kexe"
PERIOD=3
TOPOLOGY=""
TOPOLOGY_DIR="topology"
SESSION_NAME="udrip_session"
LOCK_FILE="simulation.lock"
ROUTER_IPS=("127.0.1.1" "127.0.1.2" "127.0.1.3" "127.0.1.4" "127.0.1.5")

usage() {
    echo "Usage: $0 start|stop|list [topology] [period]"
    echo "  start       Start the simulation"
    echo "  stop        Stop the simulation"
    echo "  period      Period in seconds to pass to routers (optional, default=$PERIOD)"
    echo "  list        List available topologies"
    echo "  log         List router logs and follow one"
    exit 1
}

start_router_tmux() {
    local ip=$1
    local period=$2
    local topology=$3
    local pane_name="router_${ip//./_}"

    echo "Starting router $ip in tmux pane '$pane_name'..."

    tmux new-window -t ${SESSION_NAME} -n "$pane_name" -d || true
    tmux send-keys -t ${SESSION_NAME}:"$pane_name" "clear" C-m

    CMD="$EXECUTABLE_DIR/$EXECUTABLE_NAME $ip $period $topology"
    tmux send-keys -t ${SESSION_NAME}:"$pane_name" "$CMD" C-m

    echo "  Router $ip started."
}

list_topologies() {
    if [ ! -d "$TOPOLOGY_DIR" ]; then
        echo "Topology directory '$TOPOLOGY_DIR' not found."
        return
    fi

    echo "Available topologies:"
    for dir in "$TOPOLOGY_DIR"/*/ ; do
        [ -d "$dir" ] || continue
        basename "$dir"
    done
}

follow_log() {
    echo "Available router logs:"
    local logs=(log/router_*.log)

    if [ ${#logs[@]} -eq 0 ]; then
        echo "No router logs found."
        exit 1
    fi

    local i=1
    for log_file in "${logs[@]}"; do
        # extrai IP do nome do arquivo: log/router_<ip>.log
        local ip=$(basename "$log_file" | sed -E 's/router_(.*)\.log/\1/')
        echo "  $i) $ip"
        ((i++))
    done

    read -rp "Select a router to follow (1-${#logs[@]}): " choice
    if ! [[ "$choice" =~ ^[0-9]+$ ]] || (( choice < 1 || choice > ${#logs[@]} )); then
        echo "Invalid choice."
        exit 1
    fi

    local selected_log="${logs[$((choice-1))]}"
    echo "Following log for $(basename "$selected_log") - Press Ctrl+C to stop."
    tail -f "$selected_log"
}

start_simulation() {
    if [ -f "$LOCK_FILE" ]; then
        echo "Simulation is already running. Aborting start."
        exit 1
    fi

    echo "--- Starting UDPRIP Simulation ---"

    # print topology selection
    if [ -n "$TOPOLOGY" ]; then
        echo "Using topology: $TOPOLOGY"
        echo ""
        cat "$TOPOLOGY_DIR/$TOPOLOGY/topology.txt" || { echo "Failed to read topology file. Aborting."; exit 1; }
        echo ""
    fi

    echo "Configuring loopback IPs..."
    sudo ./scripts/lo-addresses.sh del
    sudo ./scripts/lo-addresses.sh add
    sleep 2

    echo "Building the project..."
    ./gradlew build || { echo "Build failed. Aborting."; exit 1; }

    if [ ! -f "$EXECUTABLE_DIR/$EXECUTABLE_NAME" ]; then
        echo "Executable '$EXECUTABLE_NAME' not found in '$EXECUTABLE_DIR'. Aborting."
        exit 1
    fi

    if ! command -v tmux &> /dev/null; then
        echo "tmux not found. Aborting."
        exit 1
    fi

    tmux new-session -s ${SESSION_NAME} -d
    tmux send-keys -t ${SESSION_NAME} "clear" C-m
    tmux send-keys -t ${SESSION_NAME} "echo 'UDPRIP session. Use Ctrl+b n for next, Ctrl+b p for previous.'" C-m

    for ip in "${ROUTER_IPS[@]}"; do
        start_router_tmux "$ip" "$PERIOD" "$TOPOLOGY"
    done

    # Create lock file to indicate simulation is running
    touch "$LOCK_FILE"

    echo "All routers started in tmux session '${SESSION_NAME}'."
    echo "To attach: tmux attach-session -t ${SESSION_NAME}"
    echo "--- Simulation started ---"
}

stop_simulation() {
    echo "--- Stopping UDPRIP Simulation ---"

    echo "Attempting to kill tmux session '${SESSION_NAME}'..."
    tmux kill-session -t ${SESSION_NAME} || echo "tmux session '${SESSION_NAME}' not found or already closed."

    echo "Attempting to kill routers running in background via PIDs..."
    for pid_file in router_*.pid; do
        if [ -f "$pid_file" ]; then
            PID=$(cat "$pid_file")
            if kill -0 "$PID" 2>/dev/null; then
                echo "Killing process $PID..."
                kill "$PID"
            else
                echo "Process $PID not found, removing PID file."
            fi
            rm "$pid_file"
        fi
    done

    echo "Removing loopback IPs..."
    sudo ./scripts/lo-addresses.sh del

    # Remove lock file
    if [ -f "$LOCK_FILE" ]; then
        rm "$LOCK_FILE"
    fi

    echo "--- Simulation stopped ---"
}

# === MAIN ===

if [ $# -lt 1 ]; then
    usage
fi

ACTION=$1
TOPOLOGY=${2:-""}
PERIOD=${3:-$PERIOD}

case "$ACTION" in
    start)
        start_simulation
        ;;
    stop)
        stop_simulation
        ;;
    list)
        list_topologies
        ;;
    log)
        follow_log
        ;;
    *)
        usage
        ;;
esac

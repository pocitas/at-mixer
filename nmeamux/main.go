package main

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"strings"
	"sync"
	"time"
)

// Statistics shown on the console.
type Stats struct {
	mu sync.Mutex

	Aircraft      uint64
	Barometer     uint64
	GNSS          uint64
	Status        uint64
	Other         uint64
	ChecksumError uint64
	FramingError  uint64

	MockGenerated uint64
	MockRebuilt   uint64
	MockMismatch  uint64

	BytesIn   uint64
	Sentences uint64
}

func (s *Stats) print() {
	s.mu.Lock()
	defer s.mu.Unlock()

	fmt.Printf(
		"stats: aircraft=%d baro=%d gnss=%d status=%d other=%d checksum_error=%d framing_error=%d | mock: generated=%d rebuilt=%d mismatch=%d | sentences=%d bytes=%d\n",
		s.Aircraft,
		s.Barometer,
		s.GNSS,
		s.Status,
		s.Other,
		s.ChecksumError,
		s.FramingError,
		s.MockGenerated,
		s.MockRebuilt,
		s.MockMismatch,
		s.Sentences,
		s.BytesIn,
	)
}

// Check the NMEA checksum.
func validChecksum(sentence []byte) bool {
	if len(sentence) < 4 || sentence[0] != '$' {
		return false
	}

	star := bytes.LastIndexByte(sentence, '*')
	if star < 0 || star+3 > len(sentence) {
		// No checksum.
		return true
	}

	var checksum byte

	for i := 1; i < star; i++ {
		checksum ^= sentence[i]
	}

	var received byte

	_, err := fmt.Sscanf(
		string(sentence[star+1:star+3]),
		"%02X",
		&received,
	)

	if err != nil {
		return false
	}

	return checksum == received
}

// Identify the type of NMEA sentence.
func classify(sentence []byte) string {
	if len(sentence) < 2 || sentence[0] != '$' {
		return "framing_error"
	}

	s := string(sentence[1:])

	if i := strings.IndexAny(s, ",*"); i >= 0 {
		s = s[:i]
	}

	switch s {
	case "PFLAA":
		return "aircraft"

	case "PFLAU":
		return "status"

	case "LK8EX1":
		return "barometer"

	case "GGA", "GSA", "GSV", "RMC", "VTG", "GLL":
		return "gnss"

	default:
		return "other"
	}
}

// Turns arbitrary byte chunks into complete NMEA sentences.
type Framer struct {
	buffer []byte
	stats  *Stats

	// Used only by the mock test.
	mockExpected []byte
}

func (f *Framer) Feed(data []byte, output func([]byte)) {
	f.buffer = append(f.buffer, data...)

	f.stats.mu.Lock()
	f.stats.BytesIn += uint64(len(data))
	f.stats.mu.Unlock()

	// A peer that streams data without a terminator would otherwise make
	// the buffer grow without limit; no valid sentence is anywhere near this long.
	const maxBufferSize = 4096

	if len(f.buffer) > maxBufferSize {
		f.buffer = f.buffer[len(f.buffer)-maxBufferSize:]

		f.stats.mu.Lock()
		f.stats.FramingError++
		f.stats.mu.Unlock()
	}

	for {
		// Find either CR or LF as the line terminator.
		pos := bytes.IndexAny(f.buffer, "\r\n")

		if pos < 0 {
			return
		}

		terminator := f.buffer[pos]

		line := append([]byte(nil), f.buffer[:pos]...)
		f.buffer = f.buffer[pos+1:]

		// Accept both CRLF and LFCR, as well as a single CR or LF.
		if len(f.buffer) > 0 {
			if (f.buffer[0] == '\r' || f.buffer[0] == '\n') &&
				f.buffer[0] != terminator {
				f.buffer = f.buffer[1:]
			}
		}

		line = bytes.Trim(line, "\r\n")

		if len(line) == 0 {
			continue
		}

		// Keep the recoverable sentence when corruption leaves junk before '$'
		// or splices two sentences together without a terminator.
		if idx := bytes.IndexByte(line, '$'); idx > 0 {
			f.stats.mu.Lock()
			f.stats.FramingError++
			f.stats.mu.Unlock()

			line = line[idx:]
		}

		if line[0] == '$' {
			if idx := bytes.IndexByte(line[1:], '$'); idx >= 0 {
				idx++

				remainder := make([]byte, 0, len(line)-idx+1)
				remainder = append(remainder, line[idx:]...)
				remainder = append(remainder, terminator)

				f.buffer = append(remainder, f.buffer...)
				line = line[:idx]
			}
		}

		if line[0] != '$' {
			f.stats.mu.Lock()
			f.stats.FramingError++
			f.stats.mu.Unlock()
			continue
		}

		kind := classify(line)

		if kind == "framing_error" {
			f.stats.mu.Lock()
			f.stats.FramingError++
			f.stats.mu.Unlock()
			continue
		}

		if !validChecksum(line) {
			f.stats.mu.Lock()
			f.stats.ChecksumError++
			f.stats.mu.Unlock()
		}

		f.stats.mu.Lock()
		f.stats.Sentences++

		switch kind {
		case "aircraft":
			f.stats.Aircraft++
		case "barometer":
			f.stats.Barometer++
		case "gnss":
			f.stats.GNSS++
		case "status":
			f.stats.Status++
		default:
			f.stats.Other++
		}

		f.stats.mu.Unlock()

		// Add normal NMEA line ending again.
		line = append(line, '\r', '\n')

		// Check the reconstructed mock sentence.
		if f.mockExpected != nil {
			f.stats.mu.Lock()

			f.stats.MockRebuilt++

			if !bytes.Equal(line, f.mockExpected) {
				f.stats.MockMismatch++
			}

			f.stats.mu.Unlock()

			f.mockExpected = nil
		}

		output(line)
	}
}

// Multiplexer.
type Mux struct {
	stats Stats

	framersMu sync.Mutex
	framers   map[string]*Framer

	clientsMu sync.Mutex
	clients   map[net.Conn]struct{}
}

func NewMux() *Mux {
	return &Mux{
		framers: make(map[string]*Framer),
		clients: make(map[net.Conn]struct{}),
	}
}

// Feed bytes from one source into its own framer.
func (m *Mux) Feed(source string, data []byte) {
	m.framersMu.Lock()

	framer, exists := m.framers[source]

	if !exists {
		framer = &Framer{
			stats: &m.stats,
		}
		m.framers[source] = framer
	}

	m.framersMu.Unlock()

	framer.Feed(data, m.broadcast)
}

// Send data to all connected clients.
func (m *Mux) broadcast(data []byte) {
	m.clientsMu.Lock()
	defer m.clientsMu.Unlock()

	for conn := range m.clients {
		_, err := conn.Write(data)

		if err != nil {
			conn.Close()
			delete(m.clients, conn)
		}
	}
}

// TCP output server.
func (m *Mux) runServer(address string) {
	listener, err := net.Listen("tcp", address)

	if err != nil {
		log.Fatalf("cannot listen on %s: %v", address, err)
	}

	log.Printf("output listening on %s", address)

	for {
		conn, err := listener.Accept()

		if err != nil {
			log.Printf("accept error: %v", err)
			continue
		}

		m.clientsMu.Lock()
		m.clients[conn] = struct{}{}
		m.clientsMu.Unlock()

		log.Printf("output client connected: %s", conn.RemoteAddr())

		// Read only to detect disconnect.
		go func() {
			buf := make([]byte, 256)

			for {
				_, err := conn.Read(buf)

				if err != nil {
					m.clientsMu.Lock()
					delete(m.clients, conn)
					m.clientsMu.Unlock()

					conn.Close()
					log.Printf("output client disconnected")
					return
				}
			}
		}()
	}
}

// TCP input with automatic reconnect.
func (m *Mux) runTCPSource(name, address string) {
	backoff := time.Second

	for {
		log.Printf("%s: connecting to %s", name, address)

		conn, err := net.Dial("tcp", address)

		if err != nil {
			log.Printf("%s: connection failed: %v", name, err)

			time.Sleep(backoff)

			if backoff < 30*time.Second {
				backoff *= 2
			}

			continue
		}

		log.Printf("%s: connected", name)
		backoff = time.Second

		buf := make([]byte, 4096)

		for {
			n, err := conn.Read(buf)

			if n > 0 {
				m.Feed(name, buf[:n])
			}

			if err != nil {
				if err == io.EOF {
					log.Printf("%s: disconnected", name)
				} else {
					log.Printf("%s: read error: %v", name, err)
				}

				conn.Close()
				break
			}
		}
	}
}

// Generate a valid NMEA sentence.
func nmeaSentence(body string) []byte {
	var checksum byte

	for i := 0; i < len(body); i++ {
		checksum ^= body[i]
	}

	return []byte(fmt.Sprintf(
		"$%s*%02X\r\n",
		body,
		checksum,
	))
}

// Mock BLE source.
func (m *Mux) runMockBLE() {
	log.Printf("BLE: mock generator enabled")

	messages := [][]byte{
		nmeaSentence("GPGGA,123456.00,5000.000,N,01400.000,E,1,08,1.0,300.0,M,0.0,M,,"),
		nmeaSentence("PFLAA,1,100,200,300,1,ABCDEF,0,,1200,90,1"),
		nmeaSentence("LK8EX1,101325,100,0,0,0,0,0,0,0"),
		nmeaSentence("PFLAA,1,-50,100,20,1,123456,0,,500,180,1"),
		nmeaSentence("LK8EX1,101300,101,0,0,0,0,0,0,0"),
	}

	r := rand.New(rand.NewSource(time.Now().UnixNano()))

	for {
		for _, message := range messages {

			// Tell the framer what we expect to reconstruct.
			m.framersMu.Lock()

			framer := m.framers["ble"]

			if framer == nil {
				framer = &Framer{
					stats: &m.stats,
				}
				m.framers["ble"] = framer
			}

			framer.mockExpected = append([]byte(nil), message...)

			m.framersMu.Unlock()

			m.stats.mu.Lock()
			m.stats.MockGenerated++
			m.stats.mu.Unlock()

			/*
				Split the message at a random position.
				This simulates BLE packet fragmentation.
			*/
			split := 1 + r.Intn(len(message)-1)

			m.Feed("ble", message[:split])

			time.Sleep(10 * time.Millisecond)

			m.Feed("ble", message[split:])

			time.Sleep(500 * time.Millisecond)
		}
	}
}

// Print statistics periodically.
func (m *Mux) statsLoop() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		m.stats.print()
	}
}

// Send synthetic PFLAU heartbeat.
func (m *Mux) heartbeatLoop() {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	for range ticker.C {
		m.broadcast(
			nmeaSentence("PFLAU,0,1,2,1,0,,0,,"),
		)
	}
}

func main() {
	log.SetFlags(log.Ltime)

	log.Println("NMEA multiplexer starting")

	mux := NewMux()

	// Enroute reads the unified stream here.
	go mux.runServer("127.0.0.1:10112")

	// XC_Track / XC_Guide input.
	//	go mux.runTCPSource("xctrack", "127.0.0.1:10110")

	// SoftRF input
	go mux.runTCPSource("softrf", "127.0.0.1:12345")

	// Simulated SoftRF BLE input.
	//	go mux.runMockBLE()

	// Synthetic PFLAU heartbeat.
	go mux.heartbeatLoop()

	// Console statistics.
	go mux.statsLoop()

	// Keep the program running.
	select {}
}

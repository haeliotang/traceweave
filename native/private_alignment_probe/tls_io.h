#ifndef TRACEFORM_TLS_IO_H
#define TRACEFORM_TLS_IO_H

#include <emp-tool/emp-tool.h>
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <array>
#include <memory>
#include <stdexcept>

// Experimental mutual-TLS adapter. The caller provisions the peer's exact
// certificate fingerprint; this does not implement real-owner enrollment.
class TlsIO : public emp::IOChannel<TlsIO> {
    std::unique_ptr<emp::NetIO> owned;
    struct SocketOwner {
        int fd = -1;
        ~SocketOwner() { if (fd >= 0) close(fd); }
    } accepted;
    int socket_fd;
    std::unique_ptr<SSL_CTX, decltype(&SSL_CTX_free)> context;
    std::unique_ptr<SSL, decltype(&SSL_free)> connection;
    std::array<unsigned char, 65536> pending;
    size_t used = 0;

    void write_all(const void *data, size_t length) {
        const auto *bytes = static_cast<const unsigned char *>(data);
        while (length) {
            size_t written = 0;
            if (SSL_write_ex(connection.get(), bytes, length, &written) != 1 || written == 0)
                throw std::runtime_error("TLS write rejected");
            bytes += written;
            length -= written;
        }
    }

    static std::vector<std::string> &credentials() {
        static std::vector<std::string> configured;
        return configured;
    }

    static uint32_t &channel_counter() {
        static uint32_t value = 0;
        return value;
    }

    static TlsIO *&primary_channel() {
        static TlsIO *value = nullptr;
        return value;
    }

    void open_auxiliary(const char *address, int channel_port, bool quiet, uint32_t channel) {
        if (!primary_channel()) throw std::runtime_error("primary TLS channel unavailable");
        is_server = address == nullptr;
        addr = address ? address : "";
        port = channel_port;
        unsigned char sequence[4];
        for (int byte = 0; byte < 4; ++byte) sequence[byte] = channel >> (24 - byte * 8);
        if (is_server) {
            SocketOwner listener;
            listener.fd = socket(AF_INET, SOCK_STREAM, 0);
            const int reuse = 1;
            sockaddr_in endpoint{};
            endpoint.sin_family = AF_INET;
            endpoint.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            endpoint.sin_port = htons(port);
            if (listener.fd < 0 || setsockopt(listener.fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) != 0 ||
                bind(listener.fd, reinterpret_cast<sockaddr *>(&endpoint), sizeof(endpoint)) != 0 ||
                listen(listener.fd, 1) != 0)
                throw std::runtime_error("auxiliary TLS listener unavailable");
            // Notify only after bind/listen succeeds. ADB's local TCP accept
            // does not itself prove the remote port is listening.
            primary_channel()->send_data(sequence, sizeof(sequence));
            primary_channel()->flush();
            accepted.fd = accept(listener.fd, nullptr, nullptr);
            if (accepted.fd < 0) throw std::runtime_error("auxiliary TLS accept rejected");
            socket_fd = accepted.fd;
            const int one = 1;
            if (setsockopt(socket_fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one)) != 0)
                throw std::runtime_error("auxiliary TLS socket configuration rejected");
        } else {
            unsigned char ready[4];
            primary_channel()->recv_data(ready, sizeof(ready));
            if (CRYPTO_memcmp(sequence, ready, sizeof(ready)) != 0)
                throw std::runtime_error("auxiliary TLS readiness sequence rejected");
            owned.reset(new emp::NetIO(address, port, quiet));
            socket_fd = owned->consocket;
        }
    }

    void bind_channel(uint32_t channel) {
        const auto &values = credentials();
        std::array<unsigned char, 77> frame{};
        const unsigned char domain[8] = {'T', 'F', 'G', 'W', 0, 0, 0, 2};
        std::copy(domain, domain + 8, frame.begin());
        const int role = std::stoi(values[6]);
        frame[8] = role;
        for (int byte = 0; byte < 4; ++byte) frame[9 + byte] = channel >> (24 - byte * 8);
        std::copy(values[4].begin(), values[4].end(), frame.begin() + 13);
        std::copy(values[5].begin(), values[5].end(), frame.begin() + 45);
        write_all(frame.data(), frame.size());
        std::array<unsigned char, 77> peer;
        recv_data_internal(peer.data(), peer.size());
        frame[8] = 3 - role;
        if (CRYPTO_memcmp(frame.data(), peer.data(), frame.size()) != 0)
            throw std::runtime_error("TLS protocol channel binding rejected");
    }

    void authenticate(const char *certificate, const char *key,
                      const char *peer_certificate, const std::string &peer_pin) {
        if (peer_pin.size() != 64 || peer_pin.find_first_not_of("0123456789abcdef") != std::string::npos)
            throw std::runtime_error("exact peer certificate pin required");
        if (!context || SSL_CTX_set_min_proto_version(context.get(), TLS1_3_VERSION) != 1 ||
            SSL_CTX_set_max_proto_version(context.get(), TLS1_3_VERSION) != 1 ||
            SSL_CTX_use_certificate_file(context.get(), certificate, SSL_FILETYPE_PEM) != 1 ||
            SSL_CTX_use_PrivateKey_file(context.get(), key, SSL_FILETYPE_PEM) != 1 ||
            SSL_CTX_check_private_key(context.get()) != 1 ||
            SSL_CTX_load_verify_locations(context.get(), peer_certificate, nullptr) != 1)
            throw std::runtime_error("TLS identity configuration rejected");
        SSL_CTX_set_verify(context.get(), SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT, nullptr);
        connection.reset(SSL_new(context.get()));
        if (!connection || SSL_set_fd(connection.get(), socket_fd) != 1)
            throw std::runtime_error("TLS connection configuration rejected");
        if ((is_server ? SSL_accept(connection.get()) : SSL_connect(connection.get())) != 1)
            throw std::runtime_error(std::string("TLS handshake rejected: ") + ERR_error_string(ERR_get_error(), nullptr));
        if (SSL_get_verify_result(connection.get()) != X509_V_OK)
            throw std::runtime_error("TLS peer authentication rejected");
        std::unique_ptr<X509, decltype(&X509_free)> peer(SSL_get1_peer_certificate(connection.get()), X509_free);
        unsigned char digest[EVP_MAX_MD_SIZE];
        unsigned int length = 0;
        if (!peer || X509_digest(peer.get(), EVP_sha256(), digest, &length) != 1 || length != 32)
            throw std::runtime_error("TLS peer certificate unavailable");
        std::array<unsigned char, 32> expected;
        for (size_t i = 0; i < expected.size(); ++i)
            expected[i] = std::stoul(peer_pin.substr(i * 2, 2), nullptr, 16);
        if (CRYPTO_memcmp(digest, expected.data(), expected.size()) != 0)
            throw std::runtime_error("TLS peer certificate pin rejected");
        // Both local pin checks must succeed before Fpre starts opening its
        // auxiliary channels; otherwise NetIO's reconnect loop can hang.
        unsigned char ready = 1, peer_ready = 0;
        size_t count = 0;
        if (SSL_write_ex(connection.get(), &ready, 1, &count) != 1 || count != 1 ||
            SSL_read_ex(connection.get(), &peer_ready, 1, &count) != 1 || count != 1 || peer_ready != 1)
            throw std::runtime_error("TLS peer readiness rejected");
    }

public:
    bool is_server;
    std::string addr;
    int port;

    TlsIO(emp::NetIO &io, const char *certificate, const char *key,
          const char *peer_certificate, const std::string &peer_pin,
          const std::string &session, const std::string &circuit_digest, int role)
        : socket_fd(io.consocket), context(SSL_CTX_new(TLS_method()), SSL_CTX_free), connection(nullptr, SSL_free),
          is_server(io.is_server), addr(io.addr), port(io.port) {
        if (!credentials().empty()) throw std::runtime_error("one TLS participant identity per process required");
        if (session.size() != 32 || circuit_digest.size() != 32 || (role != 1 && role != 2))
            throw std::runtime_error("exact public channel binding required");
        credentials() = {certificate, key, peer_certificate, peer_pin, session, circuit_digest, std::to_string(role)};
        authenticate(certificate, key, peer_certificate, peer_pin);
        bind_channel(0);
        primary_channel() = this;
    }

    // The pinned Fpre opens auxiliary channels using this constructor. Every
    // channel authenticates the same pinned peer before exchanging MPC bytes.
    TlsIO(const char *address, int port, bool quiet)
        : socket_fd(-1), context(SSL_CTX_new(TLS_method()), SSL_CTX_free), connection(nullptr, SSL_free) {
        if (credentials().size() != 7) throw std::runtime_error("TLS participant identity unavailable");
        const auto &values = credentials();
        const uint32_t channel = ++channel_counter();
        open_auxiliary(address, port, quiet, channel);
        authenticate(values[0].c_str(), values[1].c_str(), values[2].c_str(), values[3]);
        bind_channel(channel);
    }

    void flush() {
        if (used) { write_all(pending.data(), used); used = 0; }
    }

    void send_data_internal(const void *data, size_t length) {
        const auto *bytes = static_cast<const unsigned char *>(data);
        while (length) {
            size_t count = std::min(length, pending.size() - used);
            std::copy(bytes, bytes + count, pending.data() + used);
            used += count; bytes += count; length -= count;
            if (used == pending.size()) flush();
        }
    }

    void recv_data_internal(void *data, size_t length) {
        flush();
        auto *bytes = static_cast<unsigned char *>(data);
        while (length) {
            size_t received = 0;
            if (SSL_read_ex(connection.get(), bytes, length, &received) != 1 || received == 0)
                throw std::runtime_error("TLS read rejected");
            bytes += received; length -= received;
        }
    }

    void sync() {
        unsigned char byte = 0;
        if (is_server) { send_data_internal(&byte, 1); recv_data_internal(&byte, 1); }
        else { recv_data_internal(&byte, 1); send_data_internal(&byte, 1); flush(); }
    }
};

#endif

# IGRIS Protocol
**High-Performance TCP Server for VPN and Long-Lived Connections**

## Overview

**IGRIS** is a high-performance TCP server written in **Go**, designed to handle **thousands of persistent connections** with **low overhead, predictable performance, and efficient multicore utilization**.

This project was created to solve a **real production bottleneck** in a VPN backend that initially relied on **SSH as the transport protocol**.

---

## Background & Real-World Problem

In the early versions of my **VPN application**, the backend architecture used **SSH tunneling** to transport user traffic.

From a functional and security perspective, this approach worked well.  
However, as the number of users increased, serious performance issues started to appear.

The backend had to support:
- Thousands of **long-lived TCP connections**
- Continuous bidirectional traffic
- High concurrency over extended periods

As load increased, users began experiencing **reduced speeds and instability**, while servers showed **high CPU usage and poor scalability**.

---

## Why SSH Became a Bottleneck

SSH is a secure and battle-tested protocol, but it is **not designed to be the backbone of a high-throughput VPN system**.

After profiling and monitoring the system in production, the main issues became clear:

- High protocol and encryption overhead  
- Expensive session management per connection  
- Multiple abstraction layers increasing latency  
- CPU usage scaling poorly with the number of connected clients  
- Decreasing average bandwidth per user as concurrency increased  

**In practice:**  
> The more users connected, the slower the service became.

Vertical and horizontal scaling did not solve the **core architectural limitation**.

---

## Architectural Decision

Instead of further optimizing an architecture constrained by SSH, the decision was to:

- Remove SSH as the core transport layer
- Design a **custom TCP server** optimized for:
  - Long-lived connections
  - High concurrency
  - Low per-connection overhead
  - Predictable performance under load

This decision led to the creation of **IGRIS**.

---

## The Solution: IGRIS

**IGRIS** is a TCP server built specifically for **VPN-like workloads and tunneling systems**, where connections remain open for long periods and data flows continuously.

### Design Goals

- Support **thousands of concurrent TCP connections**
- Handle **persistent (long-lived) connections**
- Efficient multicore CPU usage
- Minimal overhead per connection
- Serve as a **TCP bridge / gateway**

### Key Technical Decisions

- Controlled and intentional use of goroutines  
- Efficient, non-blocking I/O patterns  
- Explicit connection lifecycle management  
- Simple, predictable architecture focused on throughput  
- Avoidance of heavy frameworks and unnecessary abstractions  

The focus is on **clarity, stability, and performance**, not complexity.

---

## Results & Impact

After replacing the SSH-based backend with IGRIS:

- ✅ Significant reduction in CPU usage  
- ✅ Improved throughput and user-perceived speed  
- ✅ Stable behavior under high concurrency  
- ✅ Predictable performance as connections scale  
- ✅ Solid foundation for future horizontal scaling  

IGRIS was not created as a portfolio project — it emerged as a **direct response to a real production issue** and later evolved into an open-source project and technical case study.

---

## Use Cases

- VPN backends  
- TCP tunneling systems  
- Persistent connection gateways  
- High-concurrency network services  
- Custom transport layers  

---

## Future Improvements

- Load testing and benchmark reports  
- Metrics and observability (Prometheus)  
- Backpressure and rate-limiting strategies  
- Optional optimized encryption layers  

---

## Author

**Decarte Cussenha**  
Backend Engineer — Go, Networking, High-Concurrency Systems  

---

## Why This Project Matters

IGRIS demonstrates:
- Real-world problem solving  
- Deep understanding of networking and concurrency  
- Practical performance engineering in Go  
- Production-driven architectural decisions  


### made by HELIO3.AO

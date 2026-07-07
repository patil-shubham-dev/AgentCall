# PRD.md

# AgentCall MCP

## AI-to-Human Calling Infrastructure for Autonomous AI Agents

**Version:** 1.0\
**Status:** Product Requirements Document (PRD)

## 1. Executive Summary

AgentCall MCP is an open, internet-first communication platform
that enables autonomous AI agents to initiate secure voice calls to
users whenever human clarification, approval, or input is required.
Rather than relying on expensive PSTN/SIP telephony providers, the
platform uses WebRTC and the public internet for near-zero marginal call
cost.

The platform exposes a Model Context Protocol (MCP) server so AI coding
agents and autonomous workers can invoke a simple tool to request a
human conversation. Users can safely leave their workstation while an
agent continues working and are interrupted only when necessary.

## 2. Vision

Build the universal communication layer between humans and autonomous AI
systems.

## 3. Problem Statement

-   Developers constantly monitor AI progress instead of focusing on
    other work.
-   AI agents stall when they need clarification.
-   Existing AI calling providers depend on costly telephony.
-   Current coding agents primarily rely on chat notifications rather
    than real-time conversations.

## 4. Goals

-   Internet-only voice calling using WebRTC.
-   Universal MCP integration.
-   Mobile-first experience.
-   Low latency.
-   Privacy-first.
-   Open-source friendly.

## 5. Target Users

Primary: - AI developers - Solo founders - Software teams -
Researchers - Anyone running long autonomous AI tasks

## 6. User Journey

1.  User instructs AI to work autonomously.
2.  AI encounters a blocker.
3.  AI invokes AgentCall MCP.
4.  Backend creates a call session.
5.  User receives an internet call.
6.  AI explains the issue.
7.  User responds naturally.
8.  Conversation is converted into structured context.
9.  Context is returned to the waiting AI.
10. AI resumes execution automatically.

## 7. Functional Requirements

### MCP

-   CreateCall
-   ResumeTask
-   NotifyCompletion
-   CancelCall
-   QueryPresence

### Voice

-   WebRTC
-   Opus codec
-   Echo cancellation
-   Noise suppression
-   Background calling
-   Push notifications

### Clients

-   Android
-   iOS
-   Web
-   Desktop (future)

## 8. Non-functional Requirements

-   Call setup \<2 seconds
-   Voice latency \<250 ms
-   99.9% availability target
-   End-to-end encrypted media
-   Automatic reconnect

## 9. High-Level Architecture

AI Agent → MCP Server → Backend → Push Service → Mobile App → WebRTC
Voice Session → STT → LLM → Structured Response → MCP → AI Agent

## 10. Core Components

-   MCP Server
-   Authentication Service
-   Presence Service
-   Call Manager
-   Notification Service
-   WebRTC Signaling
-   STUN/TURN
-   PostgreSQL
-   Redis
-   Analytics

## 11. Security

-   OAuth
-   JWT
-   TLS
-   SRTP
-   Device pairing
-   Rate limiting

## 12. Privacy

Default: - No recording - No transcript retention - User-controlled
storage

## 13. MVP

Must Have: - MCP Server - Android app - iOS app - WebRTC calling - Push
notifications - Authentication - AI context transfer - Task resume

## 14. Success Metrics

-   Call success rate
-   Average connection time
-   Task completion rate
-   User satisfaction
-   Near-zero call cost

## 15. Long-Term Vision

Become the standard communication layer for autonomous AI systems,
allowing any AI agent to securely interrupt a human only when judgment
or clarification is required.

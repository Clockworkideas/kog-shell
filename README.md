# Kog Shell

A Spring Boot application with Spring AI integration for AI-powered shell capabilities.

## Project Structure

This project follows Domain-Driven Design (DDD) principles with the following package structure:

- `com.clockworkideas.kog.shell.domain` - Domain entities, value objects, and business logic
- `com.clockworkideas.kog.shell.application` - Application services and use cases
- `com.clockworkideas.kog.shell.infrastructure` - External concerns like databases, APIs, etc.
- `com.clockworkideas.kog.shell.interfaces` - Controllers, DTOs, and external interfaces

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher

## Getting Started

1. Clone the repository
2. Set your OpenAI API key as an environment variable:
   ```bash
   export OPENAI_API_KEY=your-api-key-here
   ```
3. Run the application:
   ```bash
   mvn spring-boot:run
   ```

## Configuration

The application can be configured through `application.properties` or environment variables. Key configuration options:

- `spring.ai.openai.api-key` - OpenAI API key for AI functionality
- `server.port` - Port for the web server (default: 8080)

## Dependencies

- Spring Boot 3.2.0
- Spring AI 1.0.0-M4
- Spring Boot Web Starter
- Spring Boot Test Starter

## License

See LICENSE file for details.
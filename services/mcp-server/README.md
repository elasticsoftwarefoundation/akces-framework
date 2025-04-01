# Akces MCP Server

The Akces Model Context Protocol (MCP) server provides language services for the Akces Framework, enhancing the developer experience in IDEs that support the MCP protocol.

## Features

- Code Completion: Get intelligent suggestions for Akces annotations, interfaces, and components
- Hover Information: View documentation and validation information when hovering over Akces elements
- Diagnostics: Get real-time validation of Akces Framework code
- Go-to-Definition: Navigate to the definition of Akces components

## Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Akces Framework

### Running the Server

```bash
mvn clean package
java -jar target/mcp-server-1.0.0.jar
```

By default, the server will run on port 8090. You can change this in the `application.properties` file.

### Configuration

The server can be configured through the `application.properties` file:

```properties
# Server settings
server.port=8090

# Spring AI MCP settings
spring.ai.mcp.server.port=${server.port}
spring.ai.mcp.server.host=localhost
spring.ai.mcp.server.mapping-path=/mcp

# Akces MCP settings
akces.mcp.diagnostics.enabled=true
akces.mcp.completion.enabled=true
akces.mcp.hover.enabled=true
akces.mcp.definition.enabled=true

# Allow remote connections
akces.mcp.allow-remote-connections=false
```

## IDE Integration

### IntelliJ IDEA

1. Install the MCP Client plugin
2. Configure the MCP server URL to `http://localhost:8090/mcp`
3. Enable the Akces MCP client in the plugin settings

### VS Code

1. Install the MCP Client extension
2. Configure the MCP server URL to `http://localhost:8090/mcp`
3. Enable the Akces MCP client in the extension settings

## Features in Detail

### Code Completion

The MCP server provides completions for:

- Akces annotations and their parameters
- Interface implementations for Akces components
- Component and type references

### Hover Information

Get detailed documentation when hovering over:

- Akces annotations
- Interface references
- Handler methods

### Diagnostics

The server validates:

- Required annotation parameters
- Interface implementation requirements
- Handler method requirements

### Go-to-Definition

Navigate to the definition of:

- Akces annotations
- Interfaces
- Component references

## Development

### Architecture

The MCP server is built using:

- Spring Boot 3.x
- Spring AI MCP Server
- Akces Framework

### Building from Source

```bash
mvn clean install
```

### Running Tests

```bash
mvn test
```

## License

This project is licensed under the terms of the license included in the Akces Framework.
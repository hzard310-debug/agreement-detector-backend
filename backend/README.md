# SMS Automation Backend

Flask API that uses Claude AI to generate intelligent SMS responses.

## Quick Start (Local Development)

1. **Install dependencies:**
   ```bash
   cd backend
   pip install -r requirements.txt
   ```

2. **Set environment variable:**
   ```bash
   # Windows PowerShell
   $env:CLAUDE_API_KEY="your-api-key-here"
   
   # Windows CMD
   set CLAUDE_API_KEY=your-api-key-here
   
   # Linux/Mac
   export CLAUDE_API_KEY=your-api-key-here
   ```

3. **Run the server:**
   ```bash
   python app.py
   ```
   
   The server will start on `http://localhost:5000`

4. **Test the health endpoint:**
   ```bash
   curl http://localhost:5000/health
   ```

## API Endpoints

- `POST /respond` - Main endpoint for generating SMS responses
- `GET /health` - Health check endpoint

## Environment Variables

- `CLAUDE_API_KEY` (required) - Your Anthropic Claude API key
- `PORT` (optional) - Server port (default: 5000)

## Deployment on Render

The `render.yaml` file is configured for automatic deployment. Make sure:
- `CLAUDE_API_KEY` is set in Render's environment variables
- The build should complete successfully after the fixes


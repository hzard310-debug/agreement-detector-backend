# Scaling to 100,000 Devices - Implementation Guide

## Architecture Changes Required

### 1. **Replace In-Memory Storage with Redis**
- Current: `sent_tracker = {}` (in-memory dictionary)
- New: Redis database for shared state across multiple instances
- Benefits: Works with horizontal scaling, persistent, fast

### 2. **Add Async Task Processing (Celery)**
- Current: Synchronous Claude API calls block requests
- New: Celery workers process Claude requests asynchronously
- Benefits: Handle bursts, better resource utilization, scale independently

### 3. **Add Response Caching**
- Cache Claude responses for similar conversations
- Reduces API calls and costs
- Improves response time

### 4. **Add Rate Limiting**
- Per-device rate limits (100 requests/hour)
- Prevents abuse and ensures fair usage
- Protects against DDoS

### 5. **Horizontal Scaling**
- Multiple Flask instances behind load balancer
- Auto-scaling based on load
- Celery workers scale independently

## Implementation Steps

### Step 1: Set up Redis
```bash
# On Render, add Redis service
# Or use external Redis (Redis Cloud, AWS ElastiCache, etc.)
```

### Step 2: Update Dependencies
Already done - `requirements.txt` includes:
- `redis==5.0.1`
- `celery==5.3.4`
- `flask-limiter==3.5.0`

### Step 3: Update Environment Variables
Add to Render:
- `REDIS_URL=redis://your-redis-url:6379/0`
- `CELERY_BROKER_URL=redis://your-redis-url:6379/0`
- `CELERY_RESULT_BACKEND=redis://your-redis-url:6379/0`

### Step 4: Deploy Multiple Instances
Update `render.yaml` to use multiple instances or use Render's auto-scaling

### Step 5: Deploy Celery Workers
Create separate Celery worker service in Render

## Capacity Estimates

### With Current Setup (2 workers):
- ~2-24 devices (100 messages each over 1 hour)

### With Optimized Setup (8 workers, Redis, Celery):
- **~8-96 devices** (100 messages each over 1 hour)
- **~200-2,400 devices** (100 messages each over 1 day)

### With Full Scaling (Multiple instances, 8 workers each, Redis, Celery):
- **~100,000 devices** (100 messages each over 1 day)
- **~10,000 devices** (100 messages each over 1 hour)

## Cost Considerations

### Render Pricing:
- Free tier: 1 instance, limited resources
- Starter: $7/month per service
- Standard: $25/month per service
- Pro: $85/month per service

### For 100k devices:
- **Recommended**: 5-10 Flask instances ($35-85/month)
- **Celery workers**: 3-5 workers ($21-35/month)
- **Redis**: External service (~$10-30/month)
- **Total**: ~$66-150/month

### Claude API Costs:
- Claude 3 Haiku: ~$0.25 per 1M input tokens, $1.25 per 1M output tokens
- Average request: ~500 input tokens, ~100 output tokens
- 100k devices × 100 messages = 10M requests
- **Estimated cost**: ~$1,250-2,500/month (depending on message length)

## Next Steps

1. ✅ Created `redis_store.py` - Redis-based storage
2. ✅ Created `celery_app.py` - Async task processing
3. ⏳ Update `app.py` to use Redis instead of `sent_tracker`
4. ⏳ Update `render.yaml` for multiple instances
5. ⏳ Create Celery worker service configuration
6. ⏳ Add monitoring and health checks



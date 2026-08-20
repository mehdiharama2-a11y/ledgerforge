package dev.ledgerforge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SensitiveRequestFilter extends OncePerRequestFilter {
  private static final long WINDOW_SECONDS=60,MAX_BODY_BYTES=262_144;
  private final Map<String,Bucket> buckets=new ConcurrentHashMap<>();
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
    String path=request.getRequestURI(); int limit="/api/auth/login".equals(path)?10:path.startsWith("/api/webhooks/")?120:0;
    if(request.getContentLengthLong()>MAX_BODY_BYTES&&"POST".equals(request.getMethod())) { response.sendError(413,"Request body too large");return; }
    if(limit>0&&"POST".equals(request.getMethod())) {
      long now=Instant.now().getEpochSecond();String key=path+":"+request.getRemoteAddr();
      Bucket bucket=buckets.compute(key,(ignored,current)->current==null||current.resetAt<=now?new Bucket(1,now+WINDOW_SECONDS):new Bucket(current.count+1,current.resetAt));
      response.setHeader("RateLimit-Limit",Integer.toString(limit));response.setHeader("RateLimit-Remaining",Integer.toString(Math.max(0,limit-bucket.count)));
      if(bucket.count>limit){response.setHeader("Retry-After",Long.toString(bucket.resetAt-now));response.sendError(429,"Too many requests");return;}
      if(buckets.size()>10_000)buckets.entrySet().removeIf(entry->entry.getValue().resetAt<=now);
    }
    chain.doFilter(request,response);
  }
  private record Bucket(int count,long resetAt){}
}

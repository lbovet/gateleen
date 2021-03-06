package org.swisspush.gateleen.hook;

import org.joda.time.LocalDateTime;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a hook.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class HttpHook {
    private String destination;
    private List<String> methods;
    private int expireAfter;
    private LocalDateTime expirationTime;
    private boolean fullUrl = false;

    /**
     * Creates a new hook.
     * 
     * @param destination destination
     */
    public HttpHook(String destination) {
        this.destination = destination;
        methods = new ArrayList<String>();
    }

    /**
     * The destination of the hook.
     * 
     * @return String
     */
    public String getDestination() {
        return destination;
    }

    /**
     * Sets the destination of the hook.
     * 
     * @param destination destination
     */
    public void setDestination(String destination) {
        this.destination = destination;
    }

    /**
     * Returns the methods which should pass the hook.
     * 
     * @return a list of HTTP methods or empty, if all methods do pass.
     */
    public List<String> getMethods() {
        return methods;
    }

    /**
     * Sets the methods which should pass the hook.
     * 
     * @param methods a list of HTTP methods or empty, if all methods do pass.
     */
    public void setMethods(List<String> methods) {
        this.methods = methods;
    }

    /**
     * Gets the expiry (x-expire-after header)
     * for the requests send to the listener.
     * 
     * @return a value in seconds
     */
    public int getExpireAfter() {
        return expireAfter;
    }

    /**
     * Sets the expiry (x-expire-after header)
     * for the requests send to the listener.
     * 
     * @param expireAfter - a value in seconds
     */
    public void setExpireAfter(int expireAfter) {
        this.expireAfter = expireAfter;
    }

    /**
     * Returns the expiration time of this hook.
     * 
     * @return expirationTime
     */
    public LocalDateTime getExpirationTime() {
        return expirationTime;
    }

    /**
     * Sets the expiration time of this hook.
     * 
     * @param expirationTime expirationTime
     */
    public void setExpirationTime(LocalDateTime expirationTime) {
        this.expirationTime = expirationTime;
    }

    /**
     * Returns whether the hook forwards using the full initial url or only the appendix.
     * 
     * @return fullUrl
     */
    public boolean isFullUrl() {
        return fullUrl;
    }

    /**
     * Sets whether the hook forwards using the full initial url or only the appendix.
     * 
     * @param fullUrl fullUrl
     */
    public void setFullUrl(boolean fullUrl) {
        this.fullUrl = fullUrl;
    }
}

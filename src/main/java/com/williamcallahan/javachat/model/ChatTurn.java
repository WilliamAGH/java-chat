package com.williamcallahan.javachat.model;

public class ChatTurn {
    private String role; // "user" or "assistant"
    private String text;

    public ChatTurn() {}
    public ChatTurn(String role, String text) { this.role = role; this.text = text; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}



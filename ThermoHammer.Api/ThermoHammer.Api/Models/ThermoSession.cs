public class ThermoSession
{
    public int Id { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public SessionState State { get; set; } = SessionState.Open;
    public required string EncryptionKey { get; set; }
}

public enum SessionState
{
    Open,
    Closed
}

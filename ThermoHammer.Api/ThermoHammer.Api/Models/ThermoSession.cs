public class ThermoSession
{
    public int Id { get; set; }
    public required DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public required SessionState State { get; set; }
    public required string EncryptionKey { get; set; }
}

public enum SessionState
{
    Open,
    Closed
}

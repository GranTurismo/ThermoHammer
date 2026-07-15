using System.Text;
using ThermoHammer.Api.Models;
using System.Security.Cryptography;

public class ThermoEncryptor
{
    private const string Chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    public string GenerateKey()
    {
        long currentUnixTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        int randomNumber1 = Random.Shared.Next(10000, 999999);
        int randomNumber2 = Random.Shared.Next(10000, 999999);
        string keyParts = (randomNumber1 + currentUnixTime + randomNumber2).ToString();
        Span<byte> buffer = Encoding.UTF8.GetBytes(keyParts).AsSpan();
        string encryptionKey = Convert.ToBase64String(buffer);

        return encryptionKey;
    }

    public bool IsValid(string encryptionKey, HammerStamp[] stamps)
    {
        if (stamps == null || string.IsNullOrEmpty(encryptionKey))
            return false;

        // Use StringBuilder to avoid massive string allocations inside the loop
        var sb = new StringBuilder();
        foreach (HammerStamp stamp in stamps)
        {
            sb.Append(Baseize(stamp.ElapsedMs.ToString()));
            sb.Append(Baseize(stamp.Score.ToString()));
            sb.Append(Baseize(stamp.ThermalState.ToString()));
        }

        // Use modern, optimized static helper which avoids instantiating/disposing SHA256 objects
        byte[] hashBytes = SHA256.HashData(Encoding.UTF8.GetBytes(sb.ToString()));
        string generatedHash = Convert.ToBase64String(hashBytes);

        // Prevent timing attacks when verifying hashes
        byte[] generatedBytes = Encoding.UTF8.GetBytes(generatedHash);
        byte[] inputBytes = Encoding.UTF8.GetBytes(encryptionKey);

        return CryptographicOperations.FixedTimeEquals(generatedBytes, inputBytes);
    }

    private string Baseize(string txt)
    {
        return Convert.ToBase64String(Encoding.UTF32.GetBytes(txt));
    }
}
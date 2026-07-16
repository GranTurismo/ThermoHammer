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

    public bool IsValid(string encryptionKey, HammerRequest hammer)
    {
        if (hammer == null || hammer.Stamps == null || string.IsNullOrEmpty(encryptionKey) || string.IsNullOrEmpty(hammer.Hash))
            return false;

        // Use StringBuilder to avoid massive string allocations inside the loop
        var sb = new StringBuilder();
        foreach (HammerStamp stamp in hammer.Stamps)
        {
            sb.Append(Baseize(stamp.ElapsedMs.ToString()));
            sb.Append(Baseize(stamp.Score.ToString()));
            sb.Append(Baseize(stamp.ThermalState.ToString()));
        }

        // Encrypt the stamp representation using HMAC-SHA256 with the session's encryption key
        byte[] keyBytes = Encoding.UTF8.GetBytes(encryptionKey);
        byte[] dataBytes = Encoding.UTF8.GetBytes(sb.ToString());

        byte[] computedHmacBytes = HMACSHA256.HashData(keyBytes, dataBytes);
        string computedHash = Convert.ToBase64String(computedHmacBytes);

        // Prevent timing attacks when verifying hashes
        byte[] generatedBytes = Encoding.UTF8.GetBytes(computedHash);
        byte[] providedBytes = Encoding.UTF8.GetBytes(hammer.Hash);

        return CryptographicOperations.FixedTimeEquals(generatedBytes, providedBytes);
    }

    private string Baseize(string txt)
    {
        return Convert.ToBase64String(Encoding.UTF32.GetBytes(txt));
    }
}
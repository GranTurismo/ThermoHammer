using System.Text.Json;
using ThermoHammer.Api.Models;

namespace ThermoHammer.Api.Tools;

public class DeviceModelResolver
{
    private readonly HttpClient _httpClient;
    private const string BaseUrl = "https://gcamatorapi.gtgroup.dev/api/values/devicebymodelnumber/";

    public DeviceModelResolver(HttpClient httpClient)
    {
        _httpClient = httpClient;
        _httpClient.Timeout = TimeSpan.FromSeconds(5);
    }

    public async Task<string> ResolveModelAsync(OsPlatform os, string deviceModel)
    {
        if (os != OsPlatform.Android || string.IsNullOrWhiteSpace(deviceModel))
        {
            return deviceModel;
        }

        try
        {
            string url = BaseUrl + Uri.EscapeDataString(deviceModel.Trim());
            using var response = await _httpClient.GetAsync(url);

            if (!response.IsSuccessStatusCode || response.StatusCode == System.Net.HttpStatusCode.NoContent)
            {
                return deviceModel;
            }

            using var stream = await response.Content.ReadAsStreamAsync();
            if (stream == null || stream.Length == 0)
            {
                return deviceModel;
            }

            using var jsonDoc = await JsonDocument.ParseAsync(stream);
            if (jsonDoc.RootElement.ValueKind == JsonValueKind.Object)
            {
                foreach (var prop in jsonDoc.RootElement.EnumerateObject())
                {
                    if (string.Equals(prop.Name, "model", StringComparison.OrdinalIgnoreCase))
                    {
                        string? modelValue = prop.Value.GetString();
                        if (!string.IsNullOrWhiteSpace(modelValue))
                        {
                            return modelValue;
                        }
                    }
                }
            }
        }
        catch
        {
            // Fallback to original model on network/parse error
        }

        return deviceModel;
    }
}

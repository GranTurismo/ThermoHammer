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
                string? manufacturer = null;
                string? modelValue = null;

                foreach (var prop in jsonDoc.RootElement.EnumerateObject())
                {
                    if (string.Equals(prop.Name, "manufacturer", StringComparison.OrdinalIgnoreCase))
                    {
                        manufacturer = prop.Value.GetString();
                    }
                    else if (string.Equals(prop.Name, "model", StringComparison.OrdinalIgnoreCase))
                    {
                        modelValue = prop.Value.GetString();
                    }
                }

                if (!string.IsNullOrWhiteSpace(modelValue))
                {
                    return CleanModel(modelValue, manufacturer);
                }
            }
        }
        catch
        {
            // Fallback to original model on network/parse error
        }

        return deviceModel;
    }

    public static string CleanModel(string model, string? manufacturer)
    {
        if (string.IsNullOrWhiteSpace(model)) return model;

        if (!string.IsNullOrWhiteSpace(manufacturer) && model.StartsWith(manufacturer, StringComparison.OrdinalIgnoreCase))
        {
            return model[manufacturer.Length..].TrimStart();
        }

        return model.Trim();
    }
}


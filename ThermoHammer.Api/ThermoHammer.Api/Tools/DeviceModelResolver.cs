using System.Text.Json;
using ThermoHammer.Api.Models;

namespace ThermoHammer.Api.Tools;

public class DeviceModelResolver
{
    private readonly HttpClient _httpClient;
    private const string BaseUrl = "https://dsapi.gtgroup.dev/api/values/devicebymodelnumber/";

    public DeviceModelResolver(HttpClient httpClient)
    {
        _httpClient = httpClient;
        _httpClient.Timeout = TimeSpan.FromSeconds(5);
    }

    /// <summary>
    /// The name a phone is stored and shown under. Android sends its hardware code behind the manufacturer ("HONOR VKP-NX9"),
    /// and gets the name on the box back ("HONOR 600 Pro"): first from the device-specs service, then from Google's list of
    /// Play devices that ships with the API (see <see cref="DeviceNames"/>). Whatever neither knows stays as it came.
    /// [manufacturer] is the one the phone reported, when the caller has it.
    /// </summary>
    public async Task<string> ResolveModelAsync(OsPlatform os, string deviceModel, string? manufacturer = null)
    {
        if (os != OsPlatform.Android || string.IsNullOrWhiteSpace(deviceModel))
        {
            return deviceModel;
        }

        // the service looks up model numbers: "VKP-NX9", never "HONOR VKP-NX9"
        if (await LookupAsync(DeviceNames.Shipped.CodeOf(deviceModel, manufacturer)) is { } found)
        {
            return found;
        }

        return DeviceNames.Shipped.TryResolve(deviceModel, manufacturer, out string name) ? name : deviceModel;
    }

    private async Task<string?> LookupAsync(string modelNumber)
    {
        try
        {
            string url = BaseUrl + Uri.EscapeDataString(modelNumber);
            using var response = await _httpClient.GetAsync(url);

            if (!response.IsSuccessStatusCode || response.StatusCode == System.Net.HttpStatusCode.NoContent)
            {
                return null;
            }

            using var stream = await response.Content.ReadAsStreamAsync();
            if (stream == null || stream.Length == 0)
            {
                return null;
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
            // the service is down or sent something odd: the caller falls back to the table
        }

        return null;
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


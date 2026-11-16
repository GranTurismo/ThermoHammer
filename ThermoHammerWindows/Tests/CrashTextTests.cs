using ThermoHammer.Engine;

namespace ThermoHammer.Tests;

/// <summary>A crash report never carries the Windows account name.</summary>
public class CrashTextTests
{
    [Fact]
    public void The_account_folder_in_a_path_is_replaced()
    {
        Assert.Equal(@"Could not find file 'C:\Users\<user>\AppData\Local\ThermoHammer\settings.json'.",
            CrashText.Scrub(@"Could not find file 'C:\Users\Giorgi Beridze\AppData\Local\ThermoHammer\settings.json'.", "Giorgi Beridze"));
        Assert.Equal("d:/users/<user>/x.txt", CrashText.Scrub("d:/users/anna/x.txt", null));
        Assert.Equal(@"\\server\Users\<user>", CrashText.Scrub(@"\\server\Users\bob", null));
    }

    [Fact]
    public void The_account_name_is_replaced_elsewhere_too()
    {
        Assert.Equal("access denied for <user> on D:\\Data", CrashText.Scrub("access denied for GIORGI on D:\\Data", "giorgi"));
    }

    [Fact]
    public void Text_without_either_is_left_alone()
    {
        const string text = "Object reference not set to an instance of an object. at ThermoHammer.Engine.StressEngine.RunSession()";
        Assert.Equal(text, CrashText.Scrub(text, "anna"));
        Assert.Equal("an error in the engine", CrashText.Scrub("an error in the engine", "an"));     // a two-letter name is not replaced
    }
}

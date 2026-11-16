using ThermoHammer.Engine;

namespace ThermoHammer.Tests;

/// <summary>An accelerator's answer against the CPU's: what counts as the same answer, and what a wrong one is told — the phones' rule.</summary>
public class AiAgreementTests
{
    private static readonly byte[] Ramp = Enumerable.Range(0, 256).Select(i => (byte)i).ToArray();
    /// <summary>An output unrelated to Ramp (correlation about 0.01).</summary>
    private static readonly byte[] Scrambled = Enumerable.Range(0, 256).Select(i => (byte)(i * 37 % 251)).ToArray();

    [Fact]
    public void Identical_scaled_and_unrelated_outputs()
    {
        Assert.Equal(1, AiAgreement.Correlation(Ramp, Ramp), 9);
        Assert.InRange(AiAgreement.Correlation(Ramp, Ramp.Select(x => (byte)(x / 2)).ToArray()), 0.99, 1.0);   // the same picture, dimmer
        Assert.InRange(Math.Abs(AiAgreement.Correlation(Ramp, Scrambled)), 0, 0.2);
        Assert.Equal(0, AiAgreement.Correlation(Ramp, Ramp[..^1]));                                           // sizes that don't line up
        Assert.Equal(0, AiAgreement.Correlation([], []));
    }

    [Fact]
    public void Constant_outputs_agree_only_when_identical()
    {
        Assert.Equal(1, AiAgreement.Correlation([7, 7, 7], [7, 7, 7]));
        Assert.Equal(0, AiAgreement.Correlation([7, 7, 7], [9, 9, 9]));
        Assert.Equal(0, AiAgreement.Correlation([7, 7, 7], [1, 2, 3]));
    }

    [Fact]
    public void The_worst_output_decides()
    {
        Assert.Equal(1, AiAgreement.Of(AiTest.Segment, [Ramp, Ramp], [Ramp, Ramp]), 9);
        Assert.True(AiAgreement.Of(AiTest.Segment, [Ramp, Ramp], [Ramp, Scrambled]) < AiAgreement.WrongBelow);
        Assert.Equal(0, AiAgreement.Of(AiTest.Segment, [Ramp], [Ramp, Ramp]));
        Assert.Equal(0, AiAgreement.Of(AiTest.Segment, [], []));
    }

    [Fact]
    public void Detection_compares_boxes_and_scores_but_not_the_class_ids()
    {
        Assert.Equal(1, AiAgreement.Of(AiTest.Detect, [Ramp, Ramp, Ramp], [Ramp, Ramp, Scrambled]), 9);
        Assert.True(AiAgreement.Of(AiTest.Detect, [Ramp, Ramp, Ramp], [Ramp, Scrambled, Ramp]) < AiAgreement.WrongBelow);
    }

    [Fact]
    public void An_answer_is_wrong_only_when_it_has_nothing_in_common_with_the_CPUs()
    {
        Assert.True(AiAgreement.IsWrong(AiAgreement.Correlation(Ramp, Scrambled)));                    // the broken GPUs and NPUs: about 0
        var drifted = Enumerable.Range(0, 256).Select(i => i % 4 != 0 ? Ramp[i] : Scrambled[i]).ToArray();   // three values in four the same
        double off = AiAgreement.Correlation(Ramp, drifted);
        Assert.InRange(off, AiAgreement.WrongBelow + 0.001, AiAgreement.Minimum - 0.001);               // a GPU's approximation: about 0.7
        Assert.False(AiAgreement.IsWrong(off));
        Assert.False(AiAgreement.IsWrong(1));
        Assert.True(AiAgreement.IsWrong(0.49));
        Assert.False(AiAgreement.IsWrong(0.5));
    }

    [Fact]
    public void A_wrong_answers_explanation() =>
        Assert.Equal("wrong answer: its result doesn't match the CPU's (agreement 0.02, a match is 1.00), so it scores 0 · it ran at 25.97/s",
            AiAgreement.WrongAnswer(25.97, 0.02));
}
